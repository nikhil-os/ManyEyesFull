import "dotenv/config";

// Fallback environment variables for Render/EC2 deployment (since .env is gitignored)
process.env.MONGO_URI = process.env.MONGO_URI || "mongodb+srv://nikhilsahupr:Nikhil%400001@cluster0.3iwof95.mongodb.net/manyeyes?appName=Cluster0";
process.env.JWT_SECRET = process.env.JWT_SECRET || "please_change_this_long_random_secret";
process.env.ADMIN_EMAIL = process.env.ADMIN_EMAIL || "admin@manyeyes.com";
process.env.ADMIN_PASSWORD = process.env.ADMIN_PASSWORD || "Admin@0001";

import express from "express";
import http from "http";
import cors from "cors";
import bcrypt from "bcryptjs";
import { connectDB } from "./config/db.js";
import authRoutes from "./routes/auth.js";
import devicesRoutes from "./routes/devices.js";
import adminRoutes from "./routes/admin.js";
import { Device } from "./models/Device.js";
import { User } from "./models/User.js";
import { decodeToken } from "./middleware/auth.js";
import { MessageTypes, makeMessage } from "./utils/messages.js";
import { WebSocketServer, WebSocket } from "ws";

const app = express();
app.use(cors());
app.use(express.json());

// Simple root route so Render shows something at '/'
app.get("/", (_, res) => res.send("ManyEyes Backend Running"));
app.get("/health", (_, res) => res.json({ ok: true }));
app.use("/auth", authRoutes);
app.use("/devices", devicesRoutes);
app.use("/admin", adminRoutes);

const server = http.createServer(app);
const wss = new WebSocketServer({ server, path: "/ws" });

// deviceId -> Set<ws>
const connections = new Map();

// Cache admin emails for fast WS filtering (refreshed on startup)
let adminEmails = new Set();

async function refreshAdminEmails() {
  const admins = await User.find({ isAdmin: true }).select("email");
  adminEmails = new Set(admins.map((u) => u.email));
}

function broadcastToGroup(email, msgObj) {
  const raw = JSON.stringify(msgObj);
  for (const [deviceId, socketSet] of connections.entries()) {
    for (const ws of socketSet) {
      if (ws.email === email && ws.readyState === WebSocket.OPEN) {
        ws.send(raw);
      }
    }
  }
}

wss.on("connection", async (ws, req) => {
  const params = new URLSearchParams(req.url.split("?")[1]);
  const token = params.get("token");
  const deviceId = params.get("deviceId");
  const decoded = decodeToken(token);
  if (!decoded || !deviceId) {
    ws.close();
    return;
  }
  ws.email = decoded.email;
  ws.deviceId = deviceId;
  ws.isAdmin = decoded.isAdmin || false;

  let set = connections.get(deviceId);
  if (!set) {
    set = new Set();
    connections.set(deviceId, set);
  }
  set.add(ws);
  console.log(`[WS] Connected deviceId=${deviceId}; sockets=${set.size} isAdmin=${ws.isAdmin}`);

  // Mark device online
  await Device.findOneAndUpdate(
    { deviceId },
    { isOnline: true, lastSeen: new Date() }
  );

  // Broadcast presence — but admin presence is only sent to admin sockets
  const presenceMsg = {
    type: MessageTypes.PRESENCE,
    deviceId,
    online: true,
  };
  if (ws.isAdmin || adminEmails.has(ws.email)) {
    // Admin coming online: only notify other admin sockets, NOT regular users
    for (const [, socketSet] of connections.entries()) {
      for (const sock of socketSet) {
        if (
          sock.email === ws.email &&
          sock.readyState === WebSocket.OPEN
        ) {
          sock.send(JSON.stringify(presenceMsg));
        }
      }
    }
  } else {
    broadcastToGroup(ws.email, presenceMsg);
  }

  ws.on("message", async (data) => {
    let msg;
    try {
      msg = JSON.parse(data);
    } catch {
      return;
    }
    const { type, toDeviceId } = msg;
    console.log(
      `[WS] RX type=${type} from=${deviceId} to=${toDeviceId} bytes=${
        typeof data === "string" ? data.length : Buffer.byteLength(data)
      }`
    );
    if (!Object.values(MessageTypes).includes(type)) return;
    // Relay targeted messages
    if (toDeviceId) {
      const targets = connections.get(toDeviceId);
      if (targets && targets.size > 0) {
        const payload = { ...msg, fromDeviceId: deviceId };
        const raw = JSON.stringify(payload);
        let delivered = 0;
        for (const sock of targets) {
          if (sock.readyState === WebSocket.OPEN) {
            try {
              sock.send(raw);
              delivered++;
            } catch (e) {
              console.error(`[WS] Relay send error to=${toDeviceId}:`, e);
            }
          }
        }
        console.log(
          `[WS] Relay ${type} from=${deviceId} to=${toDeviceId} len=${raw.length} copies=${delivered}`
        );
      } else {
        console.warn(
          `[WS] No open sockets for toDeviceId=${toDeviceId}; type=${type}`
        );
      }
    }
    // Update status for stream start/stop
    if (type === MessageTypes.REQUEST_STREAM) {
      // Viewer requesting remote device to start stream; remote will respond with OFFER
    } else if (type === MessageTypes.STOP_STREAM) {
      await Device.findOneAndUpdate(
        { deviceId },
        { "currentStatus.cameraOn": false, "currentStatus.audioOn": false }
      );
    } else if (type === MessageTypes.SWITCH_CAMERA) {
      // Target device will handle and then update status
    }
  });

  ws.on("close", async () => {
    const set = connections.get(deviceId);
    if (set) {
      set.delete(ws);
      if (set.size === 0) connections.delete(deviceId);
    }
    await Device.findOneAndUpdate(
      { deviceId },
      { isOnline: false, lastSeen: new Date() }
    );

    const offlineMsg = {
      type: MessageTypes.PRESENCE,
      deviceId,
      online: false,
    };

    // Same filtering: admin going offline only notifies admin sockets
    if (ws.isAdmin || adminEmails.has(ws.email)) {
      for (const [, socketSet] of connections.entries()) {
        for (const sock of socketSet) {
          if (sock.email === ws.email && sock.readyState === WebSocket.OPEN) {
            sock.send(JSON.stringify(offlineMsg));
          }
        }
      }
    } else {
      broadcastToGroup(ws.email, offlineMsg);
    }

    console.log(
      `[WS] Disconnected deviceId=${deviceId}; remainingSockets=${
        connections.get(deviceId)?.size || 0
      }`
    );
  });
});

// On Render, process.env.PORT is provided; default to 10000 locally if not set
const PORT = process.env.PORT || 10000;

async function bootstrapAdmin() {
  const email = process.env.ADMIN_EMAIL;
  const password = process.env.ADMIN_PASSWORD;
  if (!email || !password) return;

  let admin = await User.findOne({ email });
  const passwordHash = await bcrypt.hash(password, 10);
  
  if (!admin) {
    admin = await User.create({ email, passwordHash, isAdmin: true });
    console.log(`[Admin] Created admin user: ${email}`);
  } else {
    // Always ensure the admin has the correct password and isAdmin flag
    admin.isAdmin = true;
    admin.passwordHash = passwordHash;
    await admin.save();
    console.log(`[Admin] Ensured admin user exists and password is set: ${email}`);
  }
}

async function start() {
  await connectDB(process.env.MONGO_URI);
  await bootstrapAdmin();
  await refreshAdminEmails();
  server.listen(PORT, () => console.log(`[Server] Listening on ${PORT}`));
}

start();

import express from "express";
import { authMiddleware, adminMiddleware } from "../middleware/auth.js";
import { Device } from "../models/Device.js";
import { User } from "../models/User.js";

const router = express.Router();

// All admin routes require both auth + admin middleware
router.use(authMiddleware, adminMiddleware);

// GET /admin/users — list all users (excluding admin) with their device counts
router.get("/users", async (req, res) => {
  const users = await User.find({ isAdmin: { $ne: true } }).select(
    "email createdAt"
  );
  // Attach device info for each user
  const result = [];
  for (const u of users) {
    const devices = await Device.find({ email: u.email }).select(
      "deviceId deviceName isOnline isRevoked lastSeen"
    );
    result.push({
      email: u.email,
      createdAt: u.createdAt,
      devices,
    });
  }
  res.json(result);
});

// GET /admin/devices — return ALL devices across all emails (excluding admin devices)
router.get("/devices", async (req, res) => {
  // Find admin emails to exclude
  const adminUsers = await User.find({ isAdmin: true }).select("email");
  const adminEmails = adminUsers.map((u) => u.email);

  const devices = await Device.find({
    email: { $nin: adminEmails },
  });
  res.json(devices);
});

// POST /admin/revoke — revoke a specific device by deviceId
router.post("/revoke", async (req, res) => {
  const { deviceId } = req.body;
  if (!deviceId) return res.status(400).json({ error: "deviceId required" });

  const device = await Device.findOneAndUpdate(
    { deviceId },
    { isRevoked: true },
    { new: true }
  );
  if (!device) return res.status(404).json({ error: "Device not found" });

  res.json({ ok: true, deviceId, isRevoked: true });
});

// POST /admin/restore — restore a specific device by deviceId
router.post("/restore", async (req, res) => {
  const { deviceId } = req.body;
  if (!deviceId) return res.status(400).json({ error: "deviceId required" });

  const device = await Device.findOneAndUpdate(
    { deviceId },
    { isRevoked: false },
    { new: true }
  );
  if (!device) return res.status(404).json({ error: "Device not found" });

  res.json({ ok: true, deviceId, isRevoked: false });
});

export default router;

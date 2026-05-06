import express from "express";
import { authMiddleware } from "../middleware/auth.js";
import { Device } from "../models/Device.js";
import { User } from "../models/User.js";

const router = express.Router();

// GET /devices — returns devices for the user's email group
// Admin devices are ALWAYS excluded for regular users
router.get("/", authMiddleware, async (req, res) => {
  const email = req.user.email;

  // Find all admin emails so we can exclude their devices
  const adminUsers = await User.find({ isAdmin: true }).select("email");
  const adminEmails = new Set(adminUsers.map((u) => u.email));

  const devices = await Device.find({ email });

  // If the caller is NOT admin, strip admin devices from the list
  if (!req.user.isAdmin) {
    const filtered = devices.filter((d) => !adminEmails.has(d.email));
    return res.json(filtered);
  }

  res.json(devices);
});

// GET /devices/status — returns the revoked status for the calling user's device
router.get("/status", authMiddleware, async (req, res) => {
  const email = req.user.email;
  const devices = await Device.find({ email }).select("deviceId isRevoked deviceName");
  // Return a map of deviceId -> isRevoked for all devices belonging to this user
  const statusMap = {};
  for (const d of devices) {
    statusMap[d.deviceId] = { isRevoked: d.isRevoked || false, deviceName: d.deviceName };
  }
  res.json({ email, devices: statusMap });
});

// Deduplicate devices: keep only one device per deviceName for the user
router.post("/cleanup", authMiddleware, async (req, res) => {
  const email = req.user.email;
  const devices = await Device.find({ email }).sort({ lastSeen: -1 });

  const seen = new Map();        // deviceName → kept Device doc
  const toDelete = [];

  for (const d of devices) {
    if (!seen.has(d.deviceName)) {
      seen.set(d.deviceName, d);
    } else {
      toDelete.push(d._id);
    }
  }

  if (toDelete.length > 0) {
    await Device.deleteMany({ _id: { $in: toDelete } });
  }

  const remaining = await Device.find({ email });
  res.json({ deleted: toDelete.length, devices: remaining });
});

export default router;

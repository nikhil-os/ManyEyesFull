import express from "express";
import { authMiddleware } from "../middleware/auth.js";
import { Device } from "../models/Device.js";

const router = express.Router();

router.get("/", authMiddleware, async (req, res) => {
  const devices = await Device.find({ email: req.user.email });
  res.json(devices);
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

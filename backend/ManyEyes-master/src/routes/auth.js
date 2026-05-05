import express from "express";
import bcrypt from "bcryptjs";
import jwt from "jsonwebtoken";
import { z } from "zod";
import { User } from "../models/User.js";
import { Device } from "../models/Device.js";
import { v4 as uuid } from "uuid";

const router = express.Router();

const registerSchema = z.object({
  email: z.string().email(),
  password: z.string().min(6),
});

router.post("/register", async (req, res) => {
  const parse = registerSchema.safeParse(req.body);
  if (!parse.success)
    return res.status(400).json({ error: parse.error.errors });
  const { email, password } = parse.data;
  const existing = await User.findOne({ email });
  if (existing)
    return res.status(409).json({ error: "Email already registered" });
  const passwordHash = await bcrypt.hash(password, 10);
  await User.create({ email, passwordHash });
  return res.json({ ok: true });
});

const loginSchema = z.object({
  email: z.string().email(),
  password: z.string().min(6),
  deviceName: z.string().min(1),
  deviceId: z.string().optional(),
});

router.post("/login", async (req, res) => {
  const parse = loginSchema.safeParse(req.body);
  if (!parse.success)
    return res.status(400).json({ error: parse.error.errors });
  const { email, password, deviceName, deviceId } = parse.data;
  const user = await User.findOne({ email });
  if (!user) return res.status(401).json({ error: "Invalid credentials" });
  const match = await bcrypt.compare(password, user.passwordHash);
  if (!match) return res.status(401).json({ error: "Invalid credentials" });
  const token = jwt.sign({ email }, process.env.JWT_SECRET, {
    expiresIn: "7d",
  });
  // --- Deduplicate by deviceName ---
  // 1. If a deviceId was provided, try to find that exact device first
  let device = deviceId ? await Device.findOne({ deviceId }) : null;

  // 2. If no device found by id, look for an existing device with same email+deviceName
  if (!device) {
    device = await Device.findOne({ email, deviceName });
  }

  if (device) {
    // Reuse the existing device – update it
    device.deviceName = deviceName;
    device.isOnline = true;
    device.lastSeen = new Date();
    await device.save();
  } else {
    // Brand-new device name we haven't seen before
    const newId = deviceId || uuid();
    device = await Device.create({
      deviceId: newId,
      email,
      deviceName,
      isOnline: true,
      lastSeen: new Date(),
    });
  }

  const actualDeviceId = device.deviceId;
  const devices = await Device.find({ email });
  return res.json({ token, deviceId: actualDeviceId, devices });
});

export default router;

// ─────────────────────────────────────────────────────
// pathfinder-backend/server.js — Main Entry Point
// v1: JSON API + Protobuf Streaming
// ─────────────────────────────────────────────────────
require("dotenv").config();
const express = require("express");
const mongoose = require("mongoose");
const cors = require("cors");
const morgan = require("morgan");
const helmet = require("helmet");
const compression = require("compression");
const rateLimit = require("express-rate-limit");
const mapRoutes = require("./routes/mapRoutes");
const streamRoutes = require("./routes/streamRoutes");
const { loadProto } = require("./services/protoSerializer");

const app = express();
const PORT = process.env.PORT || 3000;

// ── Security Headers ──
app.use(helmet());

// ── Gzip Compression ──
// Compresses ALL responses (JSON + Protobuf)
// Protobuf + Gzip = ~85-90% smaller than raw JSON
app.use(compression({
  level: 6,                    // Balance between speed and compression
  threshold: 1024,             // Only compress responses > 1KB
  filter: (req, res) => {
    const type = res.getHeader("Content-Type") || "";
    // Compress both JSON and Protobuf
    if (type.includes("protobuf") || type.includes("json")) return true;
    return compression.filter(req, res);
  },
}));

// ── CORS ──
app.use(cors({
  origin: process.env.ALLOWED_ORIGINS?.split(",") || "*",
  methods: ["GET"],
}));

// ── Rate Limiting ──
const generalLimiter = rateLimit({
  windowMs: 15 * 60 * 1000,
  max: 100,
  standardHeaders: true,
  legacyHeaders: false,
  message: { error: "Too many requests. Try again in 15 minutes." },
});

const spatialLimiter = rateLimit({
  windowMs: 60 * 1000,
  max: 20,
  standardHeaders: true,
  legacyHeaders: false,
  message: { error: "Too many spatial queries. Slow down." },
});

app.use("/api/", generalLimiter);

// ── Body Parser ──
app.use(express.json({ limit: "50mb" }));
app.use(morgan(process.env.NODE_ENV === "production" ? "combined" : "dev"));

// ── API Key Middleware ──
const apiKeyAuth = (req, res, next) => {
  const apiKey = process.env.API_KEY;
  if (!apiKey) return next();

  const provided = req.headers["x-api-key"] || req.query.apiKey;
  if (provided !== apiKey) {
    return res.status(401).json({ error: "Invalid or missing API key" });
  }
  next();
};

// ── Routes ──
// v0: JSON API (original)
app.use("/api/map", apiKeyAuth, spatialLimiter, mapRoutes);

// v1: Protobuf Streaming API (new)
app.use("/api/v1", apiKeyAuth, spatialLimiter, streamRoutes);

// ── Health Check ──
app.get("/health", (req, res) => {
  res.json({
    status: "ok",
    uptime: process.uptime(),
    mongo: mongoose.connection.readyState === 1 ? "connected" : "disconnected",
    env: process.env.NODE_ENV || "development",
    apis: {
      json: "/api/map/*",
      protobuf: "/api/v1/stream-map",
    },
    compression: "gzip",
  });
});

// ── 404 Handler ──
app.use((req, res) => {
  res.status(404).json({ error: "Endpoint not found", path: req.originalUrl });
});

// ── Global Error Handler ──
app.use((err, req, res, next) => {
  console.error("Unhandled error:", err);
  res.status(500).json({ error: "Internal server error" });
});

// ── Connect to MongoDB, Load Protobuf, Start Server ──
async function startServer() {
  try {
    // Load protobuf schema
    await loadProto();

    // Connect to MongoDB
    await mongoose.connect(process.env.MONGO_URI);
    console.log("✅ MongoDB connected");

    app.listen(PORT, "0.0.0.0", () => {
      console.log(`🚀 Server running on http://0.0.0.0:${PORT}`);
      console.log(`📱 Phone access:  http://${getLocalIP()}:${PORT}`);
      console.log(`📡 JSON API:     /api/map`);
      console.log(`📦 Protobuf API: /api/v1/stream-map`);
      console.log(`🔍 Search API:   /api/map/search?q=...`);
      console.log(`🔑 API Key auth: ${process.env.API_KEY ? "ENABLED" : "DISABLED"}`);
      console.log(`🗜️  Compression:  Gzip (level 6)`);
      console.log(`🛡️  Rate limit:   100/15min (general), 20/min (spatial)`);
    });
  } catch (err) {
    console.error("❌ Startup failed:", err.message);
    process.exit(1);
  }
}

startServer();

// ── Helper: Get local WiFi IP ──
function getLocalIP() {
  const nets = require("os").networkInterfaces();
  for (const name of Object.keys(nets)) {
    for (const net of nets[name]) {
      if (net.family === "IPv4" && !net.internal) return net.address;
    }
  }
  return "localhost";
}

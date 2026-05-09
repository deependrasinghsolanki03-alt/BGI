// ─────────────────────────────────────────────────────
// pathfinder-backend/routes/streamRoutes.js
// v1 Streaming API — Protobuf binary responses
// ─────────────────────────────────────────────────────
const express = require("express");
const router = express.Router();
const streamController = require("../controllers/streamController");
const { validateRouteGraph } = require("../middleware/validate");

// GET /api/v1/stream-map?startLat=28.61&startLng=77.20&endLat=28.63&endLng=77.25&padding=1.5
// → Streams Protobuf-encoded MapGraph binary (gzip compressed)
router.get("/stream-map", validateRouteGraph, streamController.streamMapGraph);

// GET /api/v1/stream-map/info
// → JSON metadata about the streaming endpoint + protobuf schema
router.get("/stream-map/info", streamController.streamInfo);

module.exports = router;

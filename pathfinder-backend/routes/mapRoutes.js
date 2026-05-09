// ─────────────────────────────────────────────────────
// pathfinder-backend/routes/mapRoutes.js
// API Endpoints — 100% LOCAL (no external APIs)
// ─────────────────────────────────────────────────────
const express = require("express");
const router = express.Router();
const mapController = require("../controllers/mapController");
const searchController = require("../controllers/searchController");
const {
  validateRouteGraph,
  validateSubgraph,
  validateBbox,
  validateNearest,
} = require("../middleware/validate");

// ── SEARCH: Local geocoding (replaces Nominatim) ──
// GET /api/map/search?q=Red+Fort&limit=5
router.get("/search", searchController.searchPlaces);

// ── REVERSE GEOCODE: Local (replaces Nominatim reverse) ──
// GET /api/map/reverse?lat=28.6&lng=77.2
router.get("/reverse", searchController.reverseGeocode);

// ── PRIMARY: Route-specific subgraph (Start → End with padding) ──
// GET /api/map/route-graph?startLat=28.61&startLng=77.20&endLat=28.63&endLng=77.25&padding=1.5
router.get("/route-graph", validateRouteGraph, mapController.getRouteGraph);

// ── GENERAL: Radius-based subgraph ──
// GET /api/map/subgraph?lat=28.6&lng=77.2&radius=2000
router.get("/subgraph", validateSubgraph, mapController.getSubgraph);

// ── GENERAL: Manual bounding box ──
// GET /api/map/subgraph-bbox?south=28.5&west=77.1&north=28.7&east=77.3
router.get("/subgraph-bbox", validateBbox, mapController.getSubgraphByBbox);

// ── UTILITY: Nearest node snap ──
// GET /api/map/nearest?lat=28.6&lng=77.2
router.get("/nearest", validateNearest, mapController.getNearestNode);

// ── UTILITY: Database stats ──
// GET /api/map/stats
router.get("/stats", mapController.getStats);

module.exports = router;

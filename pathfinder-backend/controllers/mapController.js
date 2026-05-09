// ─────────────────────────────────────────────────────
// pathfinder-backend/controllers/mapController.js
// Logic: Spatial queries, subgraph fetching, stats
// ─────────────────────────────────────────────────────
const { MapNode, MapEdge } = require("../models/MapData");

// ═══════════════════════════════════════════════════════
// UTILITY: Convert kilometers to lat/lng degrees
// ═══════════════════════════════════════════════════════
//
// 1° latitude  ≈ 111.32 km  (constant everywhere)
// 1° longitude ≈ 111.32 km × cos(latitude)  (shrinks near poles)
//
// This utility allows specifying padding in km and
// getting accurate degree offsets for any latitude.
// ═══════════════════════════════════════════════════════

/**
 * Convert a distance in kilometers to latitude degrees.
 * @param {number} km - Distance in kilometers
 * @returns {number} Equivalent latitude degrees
 */
function kmToLatDegrees(km) {
  return km / 111.32;
}

/**
 * Convert a distance in kilometers to longitude degrees at a given latitude.
 * Accounts for the Earth's curvature — longitude degrees shrink near poles.
 * @param {number} km - Distance in kilometers
 * @param {number} atLatitude - Reference latitude (decimal degrees)
 * @returns {number} Equivalent longitude degrees
 */
function kmToLngDegrees(km, atLatitude) {
  const latRad = (atLatitude * Math.PI) / 180;
  return km / (111.32 * Math.cos(latRad));
}

/**
 * Calculate a padded bounding box between two coordinates.
 * @param {number} lat1 - Start latitude
 * @param {number} lng1 - Start longitude
 * @param {number} lat2 - End latitude
 * @param {number} lng2 - End longitude
 * @param {number} paddingKm - Buffer around the box in km (default 1.5)
 * @returns {{ south, west, north, east, paddingKm, centerLat, centerLng }}
 */
function calculatePaddedBBox(lat1, lng1, lat2, lng2, paddingKm = 1.5) {
  const minLat = Math.min(lat1, lat2);
  const maxLat = Math.max(lat1, lat2);
  const minLng = Math.min(lng1, lng2);
  const maxLng = Math.max(lng1, lng2);

  const centerLat = (minLat + maxLat) / 2;

  // Convert padding km → degrees (latitude-aware for longitude)
  const latPad = kmToLatDegrees(paddingKm);
  const lngPad = kmToLngDegrees(paddingKm, centerLat);

  return {
    south: minLat - latPad,
    north: maxLat + latPad,
    west: minLng - lngPad,
    east: maxLng + lngPad,
    paddingKm,
    centerLat,
    centerLng: (minLng + maxLng) / 2,
  };
}

// ═══════════════════════════════════════════════════════
// ROUTE GRAPH — Professional BBox + Padding endpoint
// ═══════════════════════════════════════════════════════

/**
 * GET /api/map/route-graph?startLat=28.61&startLng=77.20&endLat=28.63&endLng=77.25&padding=1.5
 *
 * Fetches a minimal subgraph between start and end points.
 * Uses a padded bounding box to capture roads for curved paths.
 *
 * Features:
 *  - Dynamic padding (default 1.5km, configurable via query)
 *  - Latitude-aware longitude padding (accounts for Earth curvature)
 *  - Mongoose projections for minimal JSON payload
 *  - Edge fetching via $or (startNode OR endNode in the node set)
 *  - Response stats for debugging
 */
exports.getRouteGraph = async (req, res) => {
  try {
    const { startLat, startLng, endLat, endLng, padding } = req.query;

    // ── Validation ──
    if (!startLat || !startLng || !endLat || !endLng) {
      return res.status(400).json({
        error: "Missing required params",
        required: "startLat, startLng, endLat, endLng",
        optional: "padding (km, default 1.5)",
      });
    }

    const sLat = parseFloat(startLat);
    const sLng = parseFloat(startLng);
    const eLat = parseFloat(endLat);
    const eLng = parseFloat(endLng);
    const padKm = Math.min(parseFloat(padding || 2.0), 25.0); // Cap at 25km padding

    // ── Calculate padded bounding box ──
    const bbox = calculatePaddedBBox(sLat, sLng, eLat, eLng, padKm);

    // ── Step 1: Fetch nodes inside bbox (with projection) ──
    // Only return fields needed by the Android A* engine
    const nodes = await MapNode.find(
      {
        location: {
          $geoWithin: {
            $box: [
              [bbox.west, bbox.south], // bottom-left  [lng, lat]
              [bbox.east, bbox.north], // top-right    [lng, lat]
            ],
          },
        },
      },
      // Projection: only return osmId + coordinates (skip tags, timestamps)
      {
        osmId: 1,
        "location.coordinates": 1,
        name: 1,
        _id: 0,
      }
    ).lean();

    if (nodes.length === 0) {
      return res.json({
        nodes: [],
        edges: [],
        bbox,
        stats: { nodeCount: 0, edgeCount: 0, queryTimeMs: 0 },
      });
    }

    // ── Step 2: Build node ID set for edge filtering ──
    const nodeIdSet = new Set(nodes.map((n) => n.osmId));
    const nodeIdArray = Array.from(nodeIdSet);

    // ── Step 3: Fetch edges connected to ANY node in the set ──
    // Uses $or so we catch edges where at least one endpoint is in-bbox.
    // Then filter client-side to keep only edges with BOTH endpoints in set.
    const edges = await MapEdge.find(
      {
        $and: [
          { startNode: { $in: nodeIdArray } },
          { endNode: { $in: nodeIdArray } },
        ],
      },
      // Projection: only return fields needed for A* + route drawing
      {
        startNode: 1,
        endNode: 1,
        distance: 1,
        trafficMultiplier: 1,
        roadQualityMultiplier: 1,
        isOneWay: 1,
        roadType: 1,
        speedKmh: 1,
        _id: 0,
      }
    ).lean();

    // ── Step 4: Format response (lightweight for mobile) ──
    const formattedNodes = nodes.map((n) => ({
      id: n.osmId,
      lat: n.location.coordinates[1],
      lng: n.location.coordinates[0],
      name: n.name || "",
    }));

    const formattedEdges = edges.map((e) => ({
      from: e.startNode,
      to: e.endNode,
      distance: e.distance,
      trafficMultiplier: e.trafficMultiplier,
      roadQualityMultiplier: e.roadQualityMultiplier,
      isOneWay: e.isOneWay,
      roadType: e.roadType,
      speedKmh: e.speedKmh,
    }));

    res.json({
      nodes: formattedNodes,
      edges: formattedEdges,
      bbox: {
        south: bbox.south,
        west: bbox.west,
        north: bbox.north,
        east: bbox.east,
        paddingKm: bbox.paddingKm,
      },
      stats: {
        nodeCount: formattedNodes.length,
        edgeCount: formattedEdges.length,
        bboxAreaKm2: (
          (bbox.north - bbox.south) * 111.32 *
          ((bbox.east - bbox.west) * 111.32 * Math.cos((bbox.centerLat * Math.PI) / 180))
        ).toFixed(2),
      },
    });
  } catch (err) {
    console.error("Error in getRouteGraph:", err);
    res.status(500).json({ error: "Failed to fetch route graph", details: err.message });
  }
};

// ═══════════════════════════════════════════════════════
// EXISTING ENDPOINTS (unchanged)
// ═══════════════════════════════════════════════════════

/**
 * GET /api/map/subgraph?lat=28.6&lng=77.2&radius=2000
 *
 * Fetches all nodes within [radius] meters of (lat, lng)
 * using MongoDB $nearSphere, then fetches all edges
 * connecting those nodes → returns a complete subgraph.
 */
exports.getSubgraph = async (req, res) => {
  try {
    const { lat, lng, radius = 2000 } = req.query;

    if (!lat || !lng) {
      return res.status(400).json({ error: "lat and lng are required" });
    }

    const latitude = parseFloat(lat);
    const longitude = parseFloat(lng);
    const maxRadius = Math.min(parseFloat(radius), 5000); // Cap at 5km

    // Step 1: Find nodes within radius using $nearSphere
    const nodes = await MapNode.find({
      location: {
        $nearSphere: {
          $geometry: {
            type: "Point",
            coordinates: [longitude, latitude], // GeoJSON: [lng, lat]
          },
          $maxDistance: maxRadius, // meters
        },
      },
    }).lean();

    if (nodes.length === 0) {
      return res.json({ nodes: [], edges: [], stats: { nodeCount: 0, edgeCount: 0 } });
    }

    // Step 2: Collect node IDs
    const nodeIds = new Set(nodes.map((n) => n.osmId));

    // Step 3: Fetch edges where BOTH start and end nodes are in the set
    const nodeIdArray = Array.from(nodeIds);
    const edges = await MapEdge.find({
      startNode: { $in: nodeIdArray },
      endNode: { $in: nodeIdArray },
    }).lean();

    // Step 4: Format response for Android app
    const formattedNodes = nodes.map((n) => ({
      id: n.osmId,
      lat: n.location.coordinates[1],
      lng: n.location.coordinates[0],
      name: n.name || "",
    }));

    const formattedEdges = edges.map((e) => ({
      from: e.startNode,
      to: e.endNode,
      distance: e.distance,
      trafficMultiplier: e.trafficMultiplier,
      roadQualityMultiplier: e.roadQualityMultiplier,
      isOneWay: e.isOneWay,
      roadType: e.roadType,
      speedKmh: e.speedKmh,
    }));

    res.json({
      nodes: formattedNodes,
      edges: formattedEdges,
      stats: {
        nodeCount: formattedNodes.length,
        edgeCount: formattedEdges.length,
        radiusUsed: maxRadius,
        center: { lat: latitude, lng: longitude },
      },
    });
  } catch (err) {
    console.error("Error in getSubgraph:", err);
    res.status(500).json({ error: "Failed to fetch subgraph", details: err.message });
  }
};

/**
 * GET /api/map/subgraph-bbox?south=28.5&west=77.1&north=28.7&east=77.3
 *
 * Fetches nodes within a bounding box using $geoWithin.
 * Better for rectangular areas (e.g., between two route points).
 */
exports.getSubgraphByBbox = async (req, res) => {
  try {
    const { south, west, north, east } = req.query;

    if (!south || !west || !north || !east) {
      return res.status(400).json({ error: "south, west, north, east are required" });
    }

    const bbox = {
      south: parseFloat(south),
      west: parseFloat(west),
      north: parseFloat(north),
      east: parseFloat(east),
    };

    // $geoWithin with $box — uses 2dsphere index
    const nodes = await MapNode.find({
      location: {
        $geoWithin: {
          $geometry: {
            type: "Polygon",
            coordinates: [
              [
                [bbox.west, bbox.south],
                [bbox.east, bbox.south],
                [bbox.east, bbox.north],
                [bbox.west, bbox.north],
                [bbox.west, bbox.south], // Close the polygon
              ],
            ],
          },
        },
      },
    }).lean();

    const nodeIds = nodes.map((n) => n.osmId);
    const edges = await MapEdge.find({
      startNode: { $in: nodeIds },
      endNode: { $in: nodeIds },
    }).lean();

    const formattedNodes = nodes.map((n) => ({
      id: n.osmId,
      lat: n.location.coordinates[1],
      lng: n.location.coordinates[0],
      name: n.name || "",
    }));

    const formattedEdges = edges.map((e) => ({
      from: e.startNode,
      to: e.endNode,
      distance: e.distance,
      trafficMultiplier: e.trafficMultiplier,
      roadQualityMultiplier: e.roadQualityMultiplier,
      isOneWay: e.isOneWay,
      roadType: e.roadType,
      speedKmh: e.speedKmh,
    }));

    res.json({
      nodes: formattedNodes,
      edges: formattedEdges,
      stats: {
        nodeCount: formattedNodes.length,
        edgeCount: formattedEdges.length,
        bbox,
      },
    });
  } catch (err) {
    console.error("Error in getSubgraphByBbox:", err);
    res.status(500).json({ error: "Failed to fetch subgraph", details: err.message });
  }
};

/**
 * GET /api/map/nearest?lat=28.6&lng=77.2
 *
 * Find the single nearest node to a coordinate.
 */
exports.getNearestNode = async (req, res) => {
  try {
    const { lat, lng } = req.query;
    if (!lat || !lng) return res.status(400).json({ error: "lat and lng required" });

    const node = await MapNode.findOne({
      location: {
        $nearSphere: {
          $geometry: { type: "Point", coordinates: [parseFloat(lng), parseFloat(lat)] },
          $maxDistance: 1000,
        },
      },
    }).lean();

    if (!node) return res.status(404).json({ error: "No node found within 1km" });

    res.json({
      id: node.osmId,
      lat: node.location.coordinates[1],
      lng: node.location.coordinates[0],
      name: node.name || "",
    });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
};

/**
 * GET /api/map/stats — Database statistics
 */
exports.getStats = async (req, res) => {
  try {
    const [nodeCount, edgeCount] = await Promise.all([
      MapNode.countDocuments(),
      MapEdge.countDocuments(),
    ]);
    res.json({ nodeCount, edgeCount });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
};

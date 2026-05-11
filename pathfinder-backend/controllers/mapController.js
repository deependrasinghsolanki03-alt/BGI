// ─────────────────────────────────────────────────────
// pathfinder-backend/controllers/mapController.js
// Logic: Spatial queries, subgraph fetching, LOD filtering
//
// Data source: OsmNode (57.8M) + OsmWay (5.7M)
// NOT MapNode/MapEdge (those are empty — never populated)
//
// LOD (Level of Detail) System:
//   Diagonal < 5km  → HIGH   → All highway types
//   Diagonal 5-15km → MEDIUM → motorway/trunk/primary/secondary/tertiary
//   Diagonal > 15km → LOW    → motorway/trunk/primary only
//
// How it prevents 27M nodes from crashing:
//   1. BBox → only nodes in the requested area (spatial index)
//   2. OsmWay filter → only ways with tags.highway matching LOD tier
//   3. Node resolution → only resolve nodeRefs that are in matched ways
//   4. Edge construction → build edges on-the-fly from way nodeRefs
//   5. Orphan pruning → remove nodes with no edges
//   6. Projection → minimal fields only
// ─────────────────────────────────────────────────────
const { OsmNode, OsmWay } = require("../models/OsmData");
const { buildLodBBox, calculateDiagonalKm, getLodTier } = require("../utils/lod");

// ═══════════════════════════════════════════════════════
// LOD Highway Type Mapping
// Maps the LOD tier to OSM tags.highway values
// ═══════════════════════════════════════════════════════

const LOD_HIGHWAY_TYPES = {
  HIGH: null, // No filter — all highway types
  MEDIUM: [
    "motorway", "motorway_link",
    "trunk", "trunk_link",
    "primary", "primary_link",
    "secondary", "secondary_link",
    "tertiary", "tertiary_link",
  ],
  LOW: [
    "motorway", "motorway_link",
    "trunk", "trunk_link",
    "primary", "primary_link",
  ],
};

// Speed estimates by road type (km/h) for travel time calculation
const SPEED_BY_TYPE = {
  motorway: 100, motorway_link: 60,
  trunk: 80, trunk_link: 50,
  primary: 60, primary_link: 40,
  secondary: 50, secondary_link: 35,
  tertiary: 40, tertiary_link: 30,
  residential: 30, unclassified: 25,
  service: 15, living_street: 15,
  footway: 5, path: 4, track: 20,
  cycleway: 15, pedestrian: 5, steps: 3,
};

// ═══════════════════════════════════════════════════════
// CORE: Build road graph from OsmWay + OsmNode
// ═══════════════════════════════════════════════════════

/**
 * Fetch roads (OsmWays) inside a BBox and build a graph.
 *
 * Pipeline:
 *   1. Query OsmWay with tags.highway filter (LOD) + nodeRefs geo-match
 *   2. Collect all unique nodeRef IDs from matching ways
 *   3. Batch-fetch OsmNode coordinates for those IDs
 *   4. Build edges from consecutive nodeRefs in each way
 *   5. Return { nodes, edges } ready for serialization
 *
 * @param {Object} bbox - { south, north, west, east }
 * @param {Object} lod - LOD tier from buildLodBBox
 * @returns {{ nodes: Array, edges: Array, stats: Object }}
 */
async function buildGraphFromOsm(bbox, lod) {
  const t0 = Date.now();

  // ══════════════════════════════════════════════════════════
  // OPTIMIZED TWO-PHASE STRATEGY:
  //
  // Phase A (Light): Fetch node IDs only (not coords) → fast
  //   Then use sampled IDs to find matching ways
  //
  // Phase B (Targeted): Resolve coordinates ONLY for nodes
  //   that appear in matched ways → minimal DB load
  //
  // This means: 25km bbox with 1M nodes in it only transfers
  // ~8MB of IDs (not 32MB of IDs+coords), and resolves coords
  // for only the ~200 nodes actually used in edges.
  // ══════════════════════════════════════════════════════════

  // ── Step 1: Fetch node IDs inside bbox (IDs only, fast) ──
  // $geometry.Polygon uses 2dsphere index
  const bboxNodes = await OsmNode.find(
    {
      location: {
        $geoWithin: {
          $geometry: {
            type: "Polygon",
            coordinates: [[
              [bbox.west, bbox.south],
              [bbox.east, bbox.south],
              [bbox.east, bbox.north],
              [bbox.west, bbox.north],
              [bbox.west, bbox.south],
            ]],
          },
        },
      },
    },
    { nodeId: 1, _id: 0 } // IDs only — no coordinates yet!
  ).lean();

  if (bboxNodes.length === 0) {
    return { nodes: [], edges: [], stats: { bboxNodes: 0, waysFetched: 0, edgesBuilt: 0, timeMs: Date.now() - t0 } };
  }

  const bboxNodeIds = new Set(bboxNodes.map((n) => n.nodeId));

  const t1 = Date.now();
  console.log(`  [LOD] Step 1: ${bboxNodes.length} nodeIds in bbox (${t1 - t0}ms)`);

  // ── Step 2: Find ways that have nodes in our bbox ──
  // Sample up to 1000 nodeIds for the $in query
  const bboxNodeArray = Array.from(bboxNodeIds);
  const sampleSize = Math.min(bboxNodeArray.length, 1000);
  const sampledIds = sampleSize < bboxNodeArray.length
    ? bboxNodeArray.filter((_, i) => i % Math.ceil(bboxNodeArray.length / sampleSize) === 0)
    : bboxNodeArray;

  const wayQuery = {
    nodeRefs: { $in: sampledIds },
  };

  // Apply LOD highway filter
  const allowedTypes = LOD_HIGHWAY_TYPES[lod.tier];
  if (allowedTypes !== null) {
    wayQuery["tags.highway"] = { $in: allowedTypes };
  } else {
    wayQuery["tags.highway"] = { $exists: true };
  }

  const maxWays = lod.tier === "LOW" ? 2000 : lod.tier === "MEDIUM" ? 5000 : 8000;
  const ways = await OsmWay.find(
    wayQuery,
    { wayId: 1, nodeRefs: 1, "tags.highway": 1, "tags.name": 1, "tags.oneway": 1, _id: 0 }
  )
    .limit(maxWays)
    .lean();

  const t2 = Date.now();
  console.log(`  [LOD] Step 2: ${ways.length} ways found (sampled ${sampledIds.length} IDs, ${t2 - t1}ms)`);

  if (ways.length === 0) {
    return { nodes: [], edges: [], stats: { bboxNodes: bboxNodeIds.size, waysFetched: 0, edgesBuilt: 0, timeMs: Date.now() - t0 } };
  }

  // ── Step 3: Collect nodeRefs we need coordinates for ──
  // Only nodes that are in both (a) the way's nodeRefs AND (b) our bbox
  const neededNodeIds = new Set();
  for (const way of ways) {
    for (const ref of way.nodeRefs) {
      if (bboxNodeIds.has(ref)) {
        neededNodeIds.add(ref);
      }
    }
  }

  // ── Step 4: Batch-fetch coordinates for needed nodes only ──
  const neededArray = Array.from(neededNodeIds);
  const nodeDocs = await OsmNode.find(
    { nodeId: { $in: neededArray } },
    { nodeId: 1, "location.coordinates": 1, _id: 0 }
  ).lean();

  const nodeCoordMap = new Map();
  for (const n of nodeDocs) {
    nodeCoordMap.set(n.nodeId, {
      lat: n.location.coordinates[1],
      lng: n.location.coordinates[0],
    });
  }

  const t3 = Date.now();
  console.log(`  [LOD] Step 3-4: Resolved ${nodeDocs.length} node coords (${t3 - t2}ms)`);

  // ── Step 5: Build edges from consecutive nodeRefs ──
  const edges = [];
  const usedNodeIds = new Set();

  for (const way of ways) {
    const highway = way.tags?.highway || "unclassified";
    const isOneWay = way.tags?.oneway === "yes";
    const speedKmh = SPEED_BY_TYPE[highway] || 25;
    const roadName = way.tags?.name || "";

    for (let i = 0; i < way.nodeRefs.length - 1; i++) {
      const fromId = way.nodeRefs[i];
      const toId = way.nodeRefs[i + 1];
      const fromCoord = nodeCoordMap.get(fromId);
      const toCoord = nodeCoordMap.get(toId);

      if (!fromCoord || !toCoord) continue;

      const dist = haversineMeters(fromCoord.lat, fromCoord.lng, toCoord.lat, toCoord.lng);

      edges.push({
        startNode: String(fromId),
        endNode: String(toId),
        distance: parseFloat(dist.toFixed(2)),
        roadType: highway,
        speedKmh,
        isOneWay,
        trafficMultiplier: 1.0,
        roadQualityMultiplier: 1.0,
        name: roadName,
        osmWayId: String(way.wayId),
      });

      usedNodeIds.add(fromId);
      usedNodeIds.add(toId);
    }
  }

  // ── Step 6: Build final node list ──
  const nodes = [];
  for (const id of usedNodeIds) {
    const coord = nodeCoordMap.get(id);
    if (coord) {
      nodes.push({
        osmId: String(id),
        location: { coordinates: [coord.lng, coord.lat] },
        name: "",
      });
    }
  }

  const t4 = Date.now();
  console.log(`  [LOD] Step 5-6: ${edges.length} edges, ${nodes.length} nodes (${t4 - t3}ms)`);

  return {
    nodes,
    edges,
    stats: {
      bboxNodes: bboxNodeIds.size,
      waysFetched: ways.length,
      nodesResolved: nodeDocs.length,
      nodesUsed: nodes.length,
      edgesBuilt: edges.length,
      timeMs: Date.now() - t0,
    },
  };
}

/**
 * Haversine distance between two lat/lng points (returns meters).
 */
function haversineMeters(lat1, lng1, lat2, lng2) {
  const R = 6371000; // Earth radius in meters
  const dLat = ((lat2 - lat1) * Math.PI) / 180;
  const dLng = ((lng2 - lng1) * Math.PI) / 180;
  const a =
    Math.sin(dLat / 2) * Math.sin(dLat / 2) +
    Math.cos((lat1 * Math.PI) / 180) * Math.cos((lat2 * Math.PI) / 180) *
    Math.sin(dLng / 2) * Math.sin(dLng / 2);
  return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
}

// ═══════════════════════════════════════════════════════
// ROUTE GRAPH — LOD-Filtered BBox Endpoint (JSON)
// ═══════════════════════════════════════════════════════

/**
 * GET /api/map/route-graph?startLat=28.61&startLng=77.20&endLat=28.63&endLng=77.25&padding=1.5
 */
exports.getRouteGraph = async (req, res) => {
  try {
    const { startLat, startLng, endLat, endLng, padding } = req.query;

    if (!startLat || !startLng || !endLat || !endLng) {
      return res.status(400).json({
        error: "Missing required params",
        required: "startLat, startLng, endLat, endLng",
        optional: "padding (km, auto if omitted)",
      });
    }

    const sLat = parseFloat(startLat);
    const sLng = parseFloat(startLng);
    const eLat = parseFloat(endLat);
    const eLng = parseFloat(endLng);
    const forcePad = padding ? parseFloat(padding) : null;
    const bbox = buildLodBBox(sLat, sLng, eLat, eLng, forcePad);

    console.log(
      `🗺️  Route graph: ${bbox.straightLineKm}km | ` +
      `BBox: ${bbox.diagonalKm}km | LOD: ${bbox.lod.tier} | Pad: ${bbox.paddingKm}km`
    );

    const result = await buildGraphFromOsm(bbox, bbox.lod);

    console.log(
      `  → ${result.stats.waysFetched} ways → ${result.nodes.length} nodes, ` +
      `${result.edges.length} edges | ${result.stats.timeMs}ms`
    );

    const formattedNodes = result.nodes.map((n) => ({
      id: n.osmId,
      lat: n.location.coordinates[1],
      lng: n.location.coordinates[0],
      name: n.name || "",
    }));

    const formattedEdges = result.edges.map((e) => ({
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
        south: bbox.south, west: bbox.west,
        north: bbox.north, east: bbox.east,
        paddingKm: bbox.paddingKm,
        diagonalKm: bbox.diagonalKm,
        lod: bbox.lod.tier,
      },
      stats: {
        nodeCount: formattedNodes.length,
        edgeCount: formattedEdges.length,
        queryTimeMs: result.stats.timeMs,
        lod: bbox.lod.tier,
        lodDescription: bbox.lod.description,
        diagonalKm: bbox.diagonalKm,
        straightLineKm: bbox.straightLineKm,
        waysFetched: result.stats.waysFetched,
      },
    });
  } catch (err) {
    console.error("Error in getRouteGraph:", err);
    res.status(500).json({ error: "Failed to fetch route graph", details: err.message });
  }
};

// ═══════════════════════════════════════════════════════
// SUBGRAPH — Radius-based with LOD
// ═══════════════════════════════════════════════════════

exports.getSubgraph = async (req, res) => {
  try {
    const { lat, lng, radius = 2000 } = req.query;
    if (!lat || !lng) {
      return res.status(400).json({ error: "lat and lng are required" });
    }

    const latitude = parseFloat(lat);
    const longitude = parseFloat(lng);
    const maxRadius = Math.min(parseFloat(radius), 15000);
    const radiusKm = maxRadius / 1000;

    // Build bbox from radius
    const { kmToLatDeg, kmToLngDeg } = require("../utils/lod");
    const latPad = kmToLatDeg(radiusKm);
    const lngPad = kmToLngDeg(radiusKm, latitude);
    const bbox = {
      south: latitude - latPad, north: latitude + latPad,
      west: longitude - lngPad, east: longitude + lngPad,
    };
    const lod = getLodTier(radiusKm * 2);

    const result = await buildGraphFromOsm(bbox, lod);

    const formattedNodes = result.nodes.map((n) => ({
      id: n.osmId,
      lat: n.location.coordinates[1],
      lng: n.location.coordinates[0],
      name: n.name || "",
    }));
    const formattedEdges = result.edges.map((e) => ({
      from: e.startNode, to: e.endNode, distance: e.distance,
      trafficMultiplier: e.trafficMultiplier,
      roadQualityMultiplier: e.roadQualityMultiplier,
      isOneWay: e.isOneWay, roadType: e.roadType, speedKmh: e.speedKmh,
    }));

    res.json({
      nodes: formattedNodes,
      edges: formattedEdges,
      stats: {
        nodeCount: formattedNodes.length,
        edgeCount: formattedEdges.length,
        radiusUsed: maxRadius,
        center: { lat: latitude, lng: longitude },
        lod: lod.tier,
      },
    });
  } catch (err) {
    console.error("Error in getSubgraph:", err);
    res.status(500).json({ error: "Failed to fetch subgraph", details: err.message });
  }
};

// ═══════════════════════════════════════════════════════
// SUBGRAPH BY BBOX with LOD
// ═══════════════════════════════════════════════════════

exports.getSubgraphByBbox = async (req, res) => {
  try {
    const { south, west, north, east } = req.query;
    if (!south || !west || !north || !east) {
      return res.status(400).json({ error: "south, west, north, east are required" });
    }

    const bbox = {
      south: parseFloat(south), west: parseFloat(west),
      north: parseFloat(north), east: parseFloat(east),
    };
    const diagonalKm = calculateDiagonalKm(bbox.south, bbox.west, bbox.north, bbox.east);
    const lod = getLodTier(diagonalKm);

    const result = await buildGraphFromOsm(bbox, lod);

    const formattedNodes = result.nodes.map((n) => ({
      id: n.osmId,
      lat: n.location.coordinates[1],
      lng: n.location.coordinates[0],
      name: n.name || "",
    }));
    const formattedEdges = result.edges.map((e) => ({
      from: e.startNode, to: e.endNode, distance: e.distance,
      trafficMultiplier: e.trafficMultiplier,
      roadQualityMultiplier: e.roadQualityMultiplier,
      isOneWay: e.isOneWay, roadType: e.roadType, speedKmh: e.speedKmh,
    }));

    res.json({
      nodes: formattedNodes,
      edges: formattedEdges,
      stats: {
        nodeCount: formattedNodes.length,
        edgeCount: formattedEdges.length,
        bbox, lod: lod.tier,
        diagonalKm: parseFloat(diagonalKm.toFixed(2)),
      },
    });
  } catch (err) {
    console.error("Error in getSubgraphByBbox:", err);
    res.status(500).json({ error: "Failed to fetch subgraph", details: err.message });
  }
};

// ═══════════════════════════════════════════════════════
// UTILITY ENDPOINTS
// ═══════════════════════════════════════════════════════

exports.getNearestNode = async (req, res) => {
  try {
    const { lat, lng } = req.query;
    if (!lat || !lng) return res.status(400).json({ error: "lat and lng required" });

    const node = await OsmNode.findOne({
      location: {
        $nearSphere: {
          $geometry: { type: "Point", coordinates: [parseFloat(lng), parseFloat(lat)] },
          $maxDistance: 1000,
        },
      },
      // Only find nodes that are part of roads (have a way reference)
    }).lean();

    if (!node) return res.status(404).json({ error: "No node found within 1km" });

    res.json({
      id: String(node.nodeId),
      lat: node.location.coordinates[1],
      lng: node.location.coordinates[0],
      name: node.tags?.name || "",
    });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
};

exports.getStats = async (req, res) => {
  try {
    const [nodeCount, wayCount] = await Promise.all([
      OsmNode.countDocuments(),
      OsmWay.countDocuments(),
    ]);
    res.json({ nodeCount, wayCount, source: "OsmNode + OsmWay (PBF import)" });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
};

// Export for use by streamController
module.exports.buildGraphFromOsm = buildGraphFromOsm;

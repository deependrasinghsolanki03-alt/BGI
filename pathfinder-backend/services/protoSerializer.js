// ─────────────────────────────────────────────────────
// pathfinder-backend/services/protoSerializer.js
// Protobuf serialization service — converts MongoDB
// documents into binary MapGraph messages
// ─────────────────────────────────────────────────────
const protobuf = require("protobufjs");
const path = require("path");

let MapGraph = null;
let protoLoaded = false;

/**
 * Load and cache the protobuf schema (once on startup).
 */
async function loadProto() {
  if (protoLoaded) return;

  const root = await protobuf.load(
    path.join(__dirname, "..", "proto", "map.proto")
  );

  MapGraph = root.lookupType("pathfinder.MapGraph");
  protoLoaded = true;
  console.log("📦 Protobuf schema loaded");
}

/**
 * Serialize nodes + edges into a Protobuf binary buffer.
 *
 * @param {Array} nodes - MongoDB MapNode documents (lean)
 * @param {Array} edges - MongoDB MapEdge documents (lean)
 * @param {Object} bbox - { south, west, north, east, paddingKm }
 * @param {number} queryTimeMs - Time taken for the MongoDB query
 * @returns {Buffer} Binary protobuf data
 */
function serializeToProtobuf(nodes, edges, bbox, queryTimeMs = 0) {
  if (!MapGraph) {
    throw new Error("Protobuf schema not loaded. Call loadProto() first.");
  }

  // Calculate bbox area
  const bboxAreaKm2 = bbox
    ? (bbox.north - bbox.south) * 111.32 *
      ((bbox.east - bbox.west) * 111.32 * Math.cos(((bbox.south + bbox.north) / 2 * Math.PI) / 180))
    : 0;

  // Build the MapGraph message
  const message = MapGraph.create({
    nodes: nodes.map((n) => ({
      id: n.osmId || n.id,
      lat: n.location ? n.location.coordinates[1] : n.lat,
      lng: n.location ? n.location.coordinates[0] : n.lng,
      name: n.name || "",
    })),
    edges: edges.map((e, idx) => ({
      id: e._id?.toString() || `e_${idx}`,
      startNode: e.startNode || e.from,
      endNode: e.endNode || e.to,
      distance: e.distance || 0,
      trafficMultiplier: e.trafficMultiplier || 1.0,
      qualityMultiplier: e.roadQualityMultiplier || 1.0,
      isOneWay: e.isOneWay || false,
      roadType: e.roadType || "",
      speedKmh: e.speedKmh || 25.0,
      geometry: [], // Can be populated with intermediate coords if available
    })),
    bbox: bbox
      ? {
          south: bbox.south,
          west: bbox.west,
          north: bbox.north,
          east: bbox.east,
          paddingKm: bbox.paddingKm || 0,
        }
      : null,
    stats: {
      nodeCount: nodes.length,
      edgeCount: edges.length,
      bboxAreaKm2: parseFloat(bboxAreaKm2.toFixed(2)),
      queryTimeMs: queryTimeMs,
      format: "protobuf",
    },
  });

  // Verify the message
  const errMsg = MapGraph.verify(message);
  if (errMsg) throw new Error(`Protobuf verification failed: ${errMsg}`);

  // Encode to binary buffer
  return MapGraph.encode(message).finish();
}

/**
 * Get the size of a protobuf buffer vs equivalent JSON for logging.
 */
function getSizeComparison(protoBuffer, nodes, edges) {
  const jsonSize = Buffer.byteLength(JSON.stringify({ nodes, edges }));
  const protoSize = protoBuffer.length;
  const savings = ((1 - protoSize / jsonSize) * 100).toFixed(1);

  return {
    jsonBytes: jsonSize,
    protoBytes: protoSize,
    savingsPercent: parseFloat(savings),
  };
}

module.exports = { loadProto, serializeToProtobuf, getSizeComparison };

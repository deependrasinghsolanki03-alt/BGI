// ─────────────────────────────────────────────────────
// pathfinder-backend/controllers/streamController.js
// Protobuf streaming endpoint — binary graph data
// ─────────────────────────────────────────────────────
const { MapNode, MapEdge } = require("../models/MapData");
const { serializeToProtobuf, getSizeComparison } = require("../services/protoSerializer");

// ── Utility: km → degrees (same as mapController) ──
function kmToLatDeg(km) {
  return km / 111.32;
}
function kmToLngDeg(km, atLat) {
  return km / (111.32 * Math.cos((atLat * Math.PI) / 180));
}

/**
 * GET /api/v1/stream-map?startLat=28.61&startLng=77.20&endLat=28.63&endLng=77.25&padding=1.5
 *
 * Streams a Protobuf-encoded MapGraph binary to the client.
 *
 * Response:
 *   Content-Type: application/x-protobuf
 *   Content-Encoding: gzip (via compression middleware)
 *   Body: Binary protobuf (MapGraph message)
 *
 * ~60-80% smaller than JSON equivalent.
 * With Gzip: ~85-90% smaller than raw JSON.
 */
exports.streamMapGraph = async (req, res) => {
  const queryStart = Date.now();

  try {
    const { startLat, startLng, endLat, endLng, padding } = req.query;

    // ── Validation (basic — full validation in middleware) ──
    if (!startLat || !startLng || !endLat || !endLng) {
      return res.status(400).json({
        error: "Missing params: startLat, startLng, endLat, endLng",
      });
    }

    const sLat = parseFloat(startLat);
    const sLng = parseFloat(startLng);
    const eLat = parseFloat(endLat);
    const eLng = parseFloat(endLng);
    const padKm = Math.min(parseFloat(padding || 2.0), 25.0); // Cap at 25km

    // ── Calculate padded bounding box ──
    const minLat = Math.min(sLat, eLat);
    const maxLat = Math.max(sLat, eLat);
    const minLng = Math.min(sLng, eLng);
    const maxLng = Math.max(sLng, eLng);
    const centerLat = (minLat + maxLat) / 2;

    const latPad = kmToLatDeg(padKm);
    const lngPad = kmToLngDeg(padKm, centerLat);

    const bbox = {
      south: minLat - latPad,
      north: maxLat + latPad,
      west: minLng - lngPad,
      east: maxLng + lngPad,
      paddingKm: padKm,
    };

    // ── Step 1: Fetch nodes inside bbox (with projection) ──
    const nodes = await MapNode.find(
      {
        location: {
          $geoWithin: {
            $box: [
              [bbox.west, bbox.south],
              [bbox.east, bbox.north],
            ],
          },
        },
      },
      { osmId: 1, "location.coordinates": 1, name: 1, _id: 0 }
    ).lean();

    if (nodes.length === 0) {
      // Return empty protobuf (not JSON error)
      const emptyBuf = serializeToProtobuf([], [], bbox, Date.now() - queryStart);
      res.set("Content-Type", "application/x-protobuf");
      res.set("X-Proto-Nodes", "0");
      res.set("X-Proto-Edges", "0");
      return res.send(Buffer.from(emptyBuf));
    }

    // ── Step 2: Build node ID set ──
    const nodeIdSet = new Set(nodes.map((n) => n.osmId));
    const nodeIdArray = Array.from(nodeIdSet);

    // ── Step 3: Fetch edges (both endpoints in set) ──
    const edges = await MapEdge.find(
      {
        startNode: { $in: nodeIdArray },
        endNode: { $in: nodeIdArray },
      },
      {
        startNode: 1,
        endNode: 1,
        distance: 1,
        trafficMultiplier: 1,
        roadQualityMultiplier: 1,
        isOneWay: 1,
        roadType: 1,
        speedKmh: 1,
        _id: 1,
      }
    ).lean();

    const queryTimeMs = Date.now() - queryStart;

    // ── Step 4: Serialize to Protobuf binary ──
    const protoBuf = serializeToProtobuf(nodes, edges, bbox, queryTimeMs);

    // ── Log size comparison ──
    const sizes = getSizeComparison(protoBuf, nodes, edges);
    console.log(
      `📦 Stream: ${nodes.length} nodes, ${edges.length} edges | ` +
      `Proto: ${(sizes.protoBytes / 1024).toFixed(1)}KB vs JSON: ${(sizes.jsonBytes / 1024).toFixed(1)}KB | ` +
      `Saved: ${sizes.savingsPercent}% | ${queryTimeMs}ms`
    );

    // ── Step 5: Send binary response ──
    res.set("Content-Type", "application/x-protobuf");
    res.set("X-Proto-Nodes", String(nodes.length));
    res.set("X-Proto-Edges", String(edges.length));
    res.set("X-Proto-Size-Bytes", String(protoBuf.length));
    res.set("X-Query-Time-Ms", String(queryTimeMs));
    res.send(Buffer.from(protoBuf));

  } catch (err) {
    console.error("Error in streamMapGraph:", err);
    // Errors still go as JSON (client can check Content-Type)
    res.status(500).json({ error: "Stream failed", details: err.message });
  }
};

/**
 * GET /api/v1/stream-map/info
 *
 * Returns metadata about the streaming endpoint (JSON).
 * Useful for the Android app to check capabilities.
 */
exports.streamInfo = async (req, res) => {
  try {
    const [nodeCount, edgeCount] = await Promise.all([
      MapNode.countDocuments(),
      MapEdge.countDocuments(),
    ]);

    res.json({
      version: "v1",
      format: "protobuf",
      protoFile: "proto/map.proto",
      compression: "gzip",
      contentType: "application/x-protobuf",
      database: {
        nodeCount,
        edgeCount,
      },
      endpoints: {
        stream: "GET /api/v1/stream-map?startLat&startLng&endLat&endLng&padding",
        info: "GET /api/v1/stream-map/info",
      },
      androidDecoding: {
        dependency: "com.google.protobuf:protobuf-javalite:4.29.3",
        decode: "MapProto.MapGraph.parseFrom(responseBody.bytes())",
        javaPackage: "com.bgi.pathfinder.proto",
      },
    });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
};

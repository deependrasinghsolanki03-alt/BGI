// ─────────────────────────────────────────────────────
// pathfinder-backend/controllers/streamController.js
// Protobuf streaming endpoint — binary graph data + LOD
//
// PRIMARY endpoint for the Android app.
// Uses buildGraphFromOsm() from mapController to query
// the REAL data (OsmNode/OsmWay), then serializes to
// Protobuf binary for ~85-90% smaller payloads.
//
// LOD Pipeline:
//   1. buildLodBBox → dynamic padding + LOD tier
//   2. buildGraphFromOsm → OsmWay highway filter + edge construction
//   3. serializeToProtobuf → binary encoding
//   4. Express gzip → final compression
//
// Result: 50km route → ~200KB proto → ~60KB gzipped
// ─────────────────────────────────────────────────────
const { serializeToProtobuf, getSizeComparison } = require("../services/protoSerializer");
const { buildLodBBox } = require("../utils/lod");
const { buildGraphFromOsm } = require("./mapController");
const { OsmNode, OsmWay } = require("../models/OsmData");

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
 * Headers:
 *   X-Proto-Nodes, X-Proto-Edges, X-Proto-Size-Bytes,
 *   X-LOD-Tier, X-Diagonal-Km, X-Query-Time-Ms
 */
exports.streamMapGraph = async (req, res) => {
  const queryStart = Date.now();

  try {
    const { startLat, startLng, endLat, endLng, padding } = req.query;

    if (!startLat || !startLng || !endLat || !endLng) {
      return res.status(400).json({
        error: "Missing params: startLat, startLng, endLat, endLng",
      });
    }

    const sLat = parseFloat(startLat);
    const sLng = parseFloat(startLng);
    const eLat = parseFloat(endLat);
    const eLng = parseFloat(endLng);

    // ── Build LOD-aware bounding box ──
    const forcePad = padding ? parseFloat(padding) : null;
    const bbox = buildLodBBox(sLat, sLng, eLat, eLng, forcePad);

    console.log(
      `📦 Stream: ${bbox.straightLineKm}km straight | ` +
      `BBox: ${bbox.diagonalKm}km | LOD: ${bbox.lod.tier} | Pad: ${bbox.paddingKm}km`
    );

    // ── Build graph from OsmNode/OsmWay ──
    const result = await buildGraphFromOsm(bbox, bbox.lod);
    const queryTimeMs = Date.now() - queryStart;

    if (result.nodes.length === 0) {
      const emptyBuf = serializeToProtobuf([], [], bbox, queryTimeMs);
      res.set("Content-Type", "application/x-protobuf");
      res.set("X-Proto-Nodes", "0");
      res.set("X-Proto-Edges", "0");
      res.set("X-LOD-Tier", bbox.lod.tier);
      return res.send(Buffer.from(emptyBuf));
    }

    // ── Serialize to Protobuf ──
    const protoBuf = serializeToProtobuf(result.nodes, result.edges, bbox, queryTimeMs);

    // ── Log comparison ──
    const sizes = getSizeComparison(protoBuf, result.nodes, result.edges);
    console.log(
      `  → LOD ${bbox.lod.tier}: ${result.stats.waysFetched} ways → ` +
      `${result.nodes.length} nodes, ${result.edges.length} edges | ` +
      `Proto: ${(sizes.protoBytes / 1024).toFixed(1)}KB vs JSON: ${(sizes.jsonBytes / 1024).toFixed(1)}KB | ` +
      `Saved: ${sizes.savingsPercent}% | ${queryTimeMs}ms`
    );

    // ── Send binary ──
    res.set("Content-Type", "application/x-protobuf");
    res.set("X-Proto-Nodes", String(result.nodes.length));
    res.set("X-Proto-Edges", String(result.edges.length));
    res.set("X-Proto-Size-Bytes", String(protoBuf.length));
    res.set("X-Query-Time-Ms", String(queryTimeMs));
    res.set("X-LOD-Tier", bbox.lod.tier);
    res.set("X-LOD-Diagonal-Km", String(bbox.diagonalKm));
    res.set("X-Ways-Fetched", String(result.stats.waysFetched));
    res.send(Buffer.from(protoBuf));

  } catch (err) {
    console.error("Error in streamMapGraph:", err);
    res.status(500).json({ error: "Stream failed", details: err.message });
  }
};

/**
 * GET /api/v1/stream-map/info
 */
exports.streamInfo = async (req, res) => {
  try {
    const [nodeCount, wayCount] = await Promise.all([
      OsmNode.countDocuments(),
      OsmWay.countDocuments(),
    ]);

    res.json({
      version: "v1",
      format: "protobuf",
      protoFile: "proto/map.proto",
      compression: "gzip",
      contentType: "application/x-protobuf",
      database: {
        nodeCount,
        wayCount,
        source: "OsmNode + OsmWay (PBF import)",
      },
      lod: {
        description: "Level of Detail — filters roads by tags.highway based on BBox size",
        tiers: {
          HIGH: "< 5km → All road types (residential, service, footway...)",
          MEDIUM: "5-15km → motorway/trunk/primary/secondary/tertiary",
          LOW: "> 15km → motorway/trunk/primary only (skeleton)",
        },
        dynamicPadding: {
          "< 2km": "1.5km", "2-5km": "2.5km", "5-15km": "4km",
          "15-30km": "8km", "30-50km": "15km", "> 50km": "25km (max)",
        },
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

// ─────────────────────────────────────────────────────
// pathfinder-backend/controllers/searchController.js
// Local search — replaces Nominatim with MongoDB queries
// Searches OsmNode name/tags + OsmWay name/tags
// Works 100% OFFLINE — no internet needed
//
// PERFORMANCE: Uses prefix-anchored regex (^query) so MongoDB
// can use the tags.name index. This is 1000x faster than
// unanchored regex on 57.8M nodes.
// ─────────────────────────────────────────────────────
const { OsmNode, OsmWay } = require("../models/OsmData");

/**
 * GET /api/map/search?q=<query>&limit=5
 *
 * Local geocoding — searches MongoDB instead of Nominatim.
 * Uses prefix-anchored regex so the tags.name index is used.
 *
 * Response format matches what the Android app expects:
 * [{ displayName, lat, lon, type }]
 */
exports.searchPlaces = async (req, res) => {
  try {
    const { q, limit = 10 } = req.query;

    if (!q || q.length < 2) {
      return res.status(400).json({ error: "Query must be at least 2 characters" });
    }

    const maxResults = Math.min(parseInt(limit) || 10, 25);
    // Escape special regex chars
    const escaped = q.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");

    const results = [];

    // ── Strategy 1: Fast prefix search on tags.name (uses index) ──
    // ^query matches names STARTING with the query — MongoDB uses the index
    const prefixRegex = new RegExp("^" + escaped, "i");

    const nodes = await OsmNode.find(
      { "tags.name": prefixRegex },
      {
        nodeId: 1,
        "location.coordinates": 1,
        "tags.name": 1,
        "tags.amenity": 1,
        "tags.shop": 1,
        "tags.place": 1,
        _id: 0,
      }
    )
      .limit(maxResults)
      .maxTimeMS(5000) // Timeout after 5 seconds
      .lean();

    for (const node of nodes) {
      const tags = node.tags || {};
      const name = tags.name || "";
      if (!name) continue;

      results.push({
        displayName: buildDisplayName(tags),
        lat: node.location.coordinates[1],
        lon: node.location.coordinates[0],
        type: tags.amenity || tags.shop || tags.place || "node",
        source: "osm_node",
        id: node.nodeId,
      });
    }

    // ── Strategy 2: If not enough results, try contains search ──
    // This is slower but catches more results
    if (results.length < maxResults) {
      const remaining = maxResults - results.length;
      const containsRegex = new RegExp(escaped, "i");
      const existingIds = new Set(results.map(r => r.id));

      const moreNodes = await OsmNode.find(
        {
          "tags.name": containsRegex,
          nodeId: { $nin: [...existingIds] }
        },
        {
          nodeId: 1,
          "location.coordinates": 1,
          "tags.name": 1,
          "tags.amenity": 1,
          "tags.shop": 1,
          "tags.place": 1,
          _id: 0,
        }
      )
        .limit(remaining)
        .maxTimeMS(5000)
        .lean();

      for (const node of moreNodes) {
        const tags = node.tags || {};
        const name = tags.name || "";
        if (!name) continue;

        results.push({
          displayName: buildDisplayName(tags),
          lat: node.location.coordinates[1],
          lon: node.location.coordinates[0],
          type: tags.amenity || tags.shop || tags.place || "node",
          source: "osm_node",
          id: node.nodeId,
        });
      }
    }

    // ── Strategy 3: Search OsmWays (named roads) ──
    if (results.length < maxResults) {
      const remaining = maxResults - results.length;
      const wayPrefixRegex = new RegExp("^" + escaped, "i");

      const ways = await OsmWay.find(
        { "tags.name": wayPrefixRegex },
        {
          wayId: 1,
          nodeRefs: { $slice: 1 },
          "tags.name": 1,
          "tags.highway": 1,
          _id: 0,
        }
      )
        .limit(remaining)
        .maxTimeMS(5000)
        .lean();

      for (const way of ways) {
        const tags = way.tags || {};
        const name = tags.name || "";
        if (!name) continue;

        let lat = 0, lon = 0;
        if (way.nodeRefs && way.nodeRefs.length > 0) {
          const firstNode = await OsmNode.findOne(
            { nodeId: way.nodeRefs[0] },
            { "location.coordinates": 1, _id: 0 }
          ).lean();
          if (firstNode) {
            lon = firstNode.location.coordinates[0];
            lat = firstNode.location.coordinates[1];
          }
        }

        if (lat !== 0 && lon !== 0) {
          results.push({
            displayName: `${name}${tags.highway ? ` (${tags.highway} road)` : ""}`,
            lat,
            lon,
            type: tags.highway || "way",
            source: "osm_way",
            id: way.wayId,
          });
        }
      }
    }

    console.log(`🔍 Search "${q}" → ${results.length} results`);
    res.json(results);
  } catch (err) {
    console.error("Search error:", err.message);
    // Return empty array instead of 500 — so app doesn't crash
    res.json([]);
  }
};

/**
 * GET /api/map/reverse?lat=28.6&lng=77.2
 *
 * Reverse geocoding — find nearest named place to a coordinate.
 * Replaces Nominatim reverse geocoding.
 */
exports.reverseGeocode = async (req, res) => {
  try {
    const { lat, lng } = req.query;

    if (!lat || !lng) {
      return res.status(400).json({ error: "lat and lng required" });
    }

    const node = await OsmNode.findOne(
      {
        location: {
          $near: {
            $geometry: {
              type: "Point",
              coordinates: [parseFloat(lng), parseFloat(lat)],
            },
            $maxDistance: 500, // 500m radius
          },
        },
        "tags.name": { $exists: true, $ne: "" },
      },
      {
        "location.coordinates": 1,
        "tags.name": 1,
        "tags.amenity": 1,
        "tags.place": 1,
        _id: 0,
      }
    ).lean();

    if (!node) {
      return res.json({ displayName: "Unknown location", lat, lon: lng });
    }

    const tags = node.tags || {};
    res.json({
      displayName: buildDisplayName(tags),
      lat: node.location.coordinates[1],
      lon: node.location.coordinates[0],
      type: tags.amenity || tags.place || "node",
    });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
};

/**
 * Build a human-readable display name from OSM tags.
 */
function buildDisplayName(tags) {
  const parts = [];
  if (tags.name) parts.push(tags.name);
  if (tags.amenity) parts.push(`(${tags.amenity})`);
  if (tags.shop) parts.push(`(${tags.shop})`);
  if (tags.place) parts.push(`(${tags.place})`);
  if (tags.highway) parts.push(`[${tags.highway}]`);
  return parts.join(" ") || "Unnamed";
}

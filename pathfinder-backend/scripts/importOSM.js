// ─────────────────────────────────────────────────────
// pathfinder-backend/scripts/importOSM.js
// Parses GeoJSON and seeds MongoDB with nodes + edges
// Optimized with compound indexes for spatial + streaming
// ─────────────────────────────────────────────────────
//
// Usage:
//   node scripts/importOSM.js <path-to-geojson-file> [--force]
//
// The GeoJSON file should contain features of type:
//   - "Point"      → imported as MapNode
//   - "LineString"  → imported as chain of MapNodes + MapEdges
//
// Export GeoJSON from:
//   1. Overpass Turbo (https://overpass-turbo.eu) → Export → GeoJSON
//   2. QGIS → Export selected features
//   3. geojson.io → Draw and export
// ─────────────────────────────────────────────────────

require("dotenv").config({ path: require("path").join(__dirname, "..", ".env") });
const fs = require("fs");
const mongoose = require("mongoose");
const { MapNode, MapEdge } = require("../models/MapData");

// ── Haversine distance (meters) ──
function haversine(lat1, lon1, lat2, lon2) {
  const R = 6371000;
  const dLat = ((lat2 - lat1) * Math.PI) / 180;
  const dLon = ((lon2 - lon1) * Math.PI) / 180;
  const a =
    Math.sin(dLat / 2) ** 2 +
    Math.cos((lat1 * Math.PI) / 180) *
      Math.cos((lat2 * Math.PI) / 180) *
      Math.sin(dLon / 2) ** 2;
  return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
}

// ── Road type → weights & speed ──
function getRoadMeta(highway) {
  const map = {
    motorway:       { traffic: 1.0, quality: 0.8, speed: 80 },
    motorway_link:  { traffic: 1.0, quality: 0.8, speed: 60 },
    trunk:          { traffic: 1.3, quality: 0.9, speed: 60 },
    trunk_link:     { traffic: 1.3, quality: 0.9, speed: 45 },
    primary:        { traffic: 1.5, quality: 1.0, speed: 45 },
    primary_link:   { traffic: 1.5, quality: 1.0, speed: 35 },
    secondary:      { traffic: 1.2, quality: 1.1, speed: 35 },
    secondary_link: { traffic: 1.2, quality: 1.1, speed: 30 },
    tertiary:       { traffic: 1.0, quality: 1.2, speed: 30 },
    tertiary_link:  { traffic: 1.0, quality: 1.2, speed: 25 },
    residential:    { traffic: 0.8, quality: 1.5, speed: 20 },
    unclassified:   { traffic: 0.7, quality: 1.8, speed: 15 },
  };
  return map[highway] || { traffic: 1.0, quality: 1.3, speed: 25 };
}

// ── Main Import Function ──
async function importGeoJSON(filePath) {
  console.log(`\n📂 Reading: ${filePath}`);
  const raw = fs.readFileSync(filePath, "utf-8");
  const geojson = JSON.parse(raw);

  const features = geojson.features || geojson;
  if (!Array.isArray(features)) {
    throw new Error("Invalid GeoJSON: expected features array");
  }

  console.log(`📊 Found ${features.length} features`);

  // Connect to MongoDB
  await mongoose.connect(process.env.MONGO_URI);
  console.log("✅ MongoDB connected");

  // Clear existing data if --force
  const clearConfirm = process.argv.includes("--force");
  if (clearConfirm) {
    await MapNode.deleteMany({});
    await MapEdge.deleteMany({});
    console.log("🗑️  Cleared existing data");
  }

  let nodeCount = 0;
  let edgeCount = 0;
  const nodeBatch = [];
  const edgeBatch = [];
  const seenNodes = new Set();
  let edgeIdCounter = 0;

  for (const feature of features) {
    const geom = feature.geometry;
    const props = feature.properties || {};

    if (!geom) continue;

    if (geom.type === "Point") {
      // ── Import as a standalone node ──
      const [lon, lat] = geom.coordinates;
      const osmId = String(feature.id || props.id || `pt_${lon}_${lat}`);

      if (!seenNodes.has(osmId)) {
        seenNodes.add(osmId);
        nodeBatch.push({
          osmId,
          location: { type: "Point", coordinates: [lon, lat] },
          name: props.name || "",
          tags: props,
        });
      }
    } else if (geom.type === "LineString") {
      // ── Import as a chain of nodes + edges ──
      const coords = geom.coordinates;
      const highway = props.highway || "unclassified";
      const roadMeta = getRoadMeta(highway);
      const isOneWay = props.oneway === "yes" || props.oneway === "1" || props.junction === "roundabout";
      const wayId = String(feature.id || props.id || `way_${Date.now()}_${Math.random()}`);

      for (let i = 0; i < coords.length; i++) {
        const [lon, lat] = coords[i];
        const nodeId = `${lon.toFixed(7)}_${lat.toFixed(7)}`;

        if (!seenNodes.has(nodeId)) {
          seenNodes.add(nodeId);
          nodeBatch.push({
            osmId: nodeId,
            location: { type: "Point", coordinates: [lon, lat] },
            name: "",
            tags: {},
          });
        }

        // Create edge between consecutive nodes
        if (i > 0) {
          const [prevLon, prevLat] = coords[i - 1];
          const prevId = `${prevLon.toFixed(7)}_${prevLat.toFixed(7)}`;
          const dist = haversine(prevLat, prevLon, lat, lon);
          edgeIdCounter++;

          edgeBatch.push({
            startNode: prevId,
            endNode: nodeId,
            distance: dist,
            roadType: highway,
            trafficMultiplier: roadMeta.traffic,
            roadQualityMultiplier: roadMeta.quality,
            speedKmh: roadMeta.speed,
            isOneWay,
            osmWayId: wayId,
            name: props.name || "",
          });

          // Add reverse edge if not one-way
          if (!isOneWay) {
            edgeIdCounter++;
            edgeBatch.push({
              startNode: nodeId,
              endNode: prevId,
              distance: dist,
              roadType: highway,
              trafficMultiplier: roadMeta.traffic,
              roadQualityMultiplier: roadMeta.quality,
              speedKmh: roadMeta.speed,
              isOneWay: false,
              osmWayId: wayId,
              name: props.name || "",
            });
          }
        }
      }
    }

    // Batch insert every 5000 items
    if (nodeBatch.length >= 5000) {
      await MapNode.insertMany(nodeBatch, { ordered: false }).catch(() => {});
      nodeCount += nodeBatch.length;
      process.stdout.write(`\r  📌 Nodes: ${nodeCount} | 🔗 Edges: ${edgeCount}`);
      nodeBatch.length = 0;
    }
    if (edgeBatch.length >= 5000) {
      await MapEdge.insertMany(edgeBatch, { ordered: false }).catch(() => {});
      edgeCount += edgeBatch.length;
      process.stdout.write(`\r  📌 Nodes: ${nodeCount} | 🔗 Edges: ${edgeCount}`);
      edgeBatch.length = 0;
    }
  }

  // Insert remaining
  if (nodeBatch.length > 0) {
    await MapNode.insertMany(nodeBatch, { ordered: false }).catch(() => {});
    nodeCount += nodeBatch.length;
  }
  if (edgeBatch.length > 0) {
    await MapEdge.insertMany(edgeBatch, { ordered: false }).catch(() => {});
    edgeCount += edgeBatch.length;
  }

  console.log(`\n\n✅ Import complete!`);
  console.log(`   📌 Nodes: ${nodeCount}`);
  console.log(`   🔗 Edges: ${edgeCount}`);

  // ── Create all indexes for lightning-fast queries ──
  console.log("\n📐 Creating indexes...");

  // 2dsphere for $geoWithin / $nearSphere
  await MapNode.collection.createIndex({ location: "2dsphere" });
  console.log("   ✅ MapNode: 2dsphere (location)");

  // osmId unique index for fast lookup
  await MapNode.collection.createIndex({ osmId: 1 }, { unique: true });
  console.log("   ✅ MapNode: unique (osmId)");

  // Compound index for edge lookups by both endpoints
  await MapEdge.collection.createIndex({ startNode: 1, endNode: 1 });
  console.log("   ✅ MapEdge: compound (startNode + endNode)");

  // Individual indexes for $in queries on single fields
  await MapEdge.collection.createIndex({ startNode: 1 });
  console.log("   ✅ MapEdge: single (startNode)");

  await MapEdge.collection.createIndex({ endNode: 1 });
  console.log("   ✅ MapEdge: single (endNode)");

  // Road type index (for filtering by road category)
  await MapEdge.collection.createIndex({ roadType: 1 });
  console.log("   ✅ MapEdge: single (roadType)");

  await mongoose.disconnect();
  console.log("\n🔌 Disconnected from MongoDB");
}

// ── CLI Entry Point ──
const filePath = process.argv[2];
if (!filePath) {
  console.log("Usage: node scripts/importOSM.js <geojson-file> [--force]");
  console.log("  --force  Clear existing data before import");
  console.log("\nExample:");
  console.log("  node scripts/importOSM.js delhi_roads.geojson --force");
  process.exit(1);
}

importGeoJSON(filePath).catch((err) => {
  console.error("❌ Import failed:", err.message);
  process.exit(1);
});

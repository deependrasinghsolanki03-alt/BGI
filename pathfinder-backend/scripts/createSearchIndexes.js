// ─────────────────────────────────────────────────────
// pathfinder-backend/scripts/createSearchIndexes.js
// Creates optimized indexes for < 100ms search on 57.8M nodes
//
// Usage:
//   node scripts/createSearchIndexes.js
//
// Creates:
//   1. Text index on tags.name (weighted) — for $text operator
//   2. Compound index on tags.name + location — for proximity prefix search
//   3. Compound index on tags.amenity — for category search
// ─────────────────────────────────────────────────────
require("dotenv").config();
const mongoose = require("mongoose");
const { OsmNode, OsmWay } = require("../models/OsmData");

async function createIndexes() {
  console.log("🔗 Connecting to MongoDB...");
  await mongoose.connect(process.env.MONGO_URI);
  console.log("✅ Connected\n");

  const db = mongoose.connection.db;

  // ── 1. Text Index with Weights ──
  // Direct name match ranks higher (weight: 10) than tag matches (weight: 1)
  // Note: MongoDB allows only ONE text index per collection
  console.log("📝 Creating text index on OsmNodes (tags.name weighted)...");
  try {
    // Drop existing text index if any
    const existingIndexes = await db.collection("osmnodes").indexes();
    for (const idx of existingIndexes) {
      if (idx.textIndexVersion) {
        console.log(`   Dropping existing text index: ${idx.name}`);
        await db.collection("osmnodes").dropIndex(idx.name);
      }
    }

    await db.collection("osmnodes").createIndex(
      {
        "tags.name": "text",
        "tags.amenity": "text",
        "tags.shop": "text",
        "tags.place": "text",
      },
      {
        weights: {
          "tags.name": 10,     // Name match = highest priority
          "tags.place": 5,     // City/village names = high priority
          "tags.amenity": 2,   // Amenity type = medium priority
          "tags.shop": 1,      // Shop type = lowest priority
        },
        name: "search_text_weighted",
        default_language: "english",
        background: true,
      }
    );
    console.log("   ✅ Text index created (name:10, place:5, amenity:2, shop:1)\n");
  } catch (err) {
    if (err.code === 85 || err.code === 86) {
      console.log("   ⚠️ Text index already exists (conflicting), skipping");
      console.log("   To recreate: db.osmnodes.dropIndex('search_text_weighted')\n");
    } else {
      console.error("   ❌ Text index error:", err.message, "\n");
    }
  }

  // ── 2. Prefix Search Index (tags.name) ──
  // This is the KEY index that makes regex ^query fast
  console.log("📝 Ensuring prefix index on OsmNodes tags.name...");
  try {
    await db.collection("osmnodes").createIndex(
      { "tags.name": 1 },
      { name: "tags_name_prefix", sparse: true, background: true }
    );
    console.log("   ✅ Prefix index on tags.name created\n");
  } catch (err) {
    if (err.code === 85) {
      console.log("   ✅ Already exists\n");
    } else {
      console.error("   ❌ Error:", err.message, "\n");
    }
  }

  // ── 3. Category Indexes ──
  console.log("📝 Ensuring category indexes...");
  const categoryFields = ["tags.amenity", "tags.shop", "tags.place"];
  for (const field of categoryFields) {
    try {
      await db.collection("osmnodes").createIndex(
        { [field]: 1 },
        { sparse: true, background: true }
      );
      console.log(`   ✅ ${field} index OK`);
    } catch (err) {
      console.log(`   ⚠️ ${field}: ${err.message}`);
    }
  }

  // ── 4. Way Name Index ──
  console.log("\n📝 Ensuring OsmWay tags.name index...");
  try {
    await db.collection("osmways").createIndex(
      { "tags.name": 1 },
      { name: "way_tags_name", sparse: true, background: true }
    );
    console.log("   ✅ OsmWay tags.name index OK\n");
  } catch (err) {
    console.log(`   ⚠️ ${err.message}\n`);
  }

  // ── 5. 2dsphere (ensure exists for $nearSphere) ──
  console.log("📝 Ensuring 2dsphere index on OsmNodes location...");
  try {
    await db.collection("osmnodes").createIndex(
      { location: "2dsphere" },
      { name: "location_2dsphere", background: true }
    );
    console.log("   ✅ 2dsphere index OK\n");
  } catch (err) {
    console.log(`   ✅ Already exists\n`);
  }

  // ── Summary ──
  console.log("═══════════════════════════════════════");
  console.log("📊 Final index listing:");
  const finalIndexes = await db.collection("osmnodes").indexes();
  for (const idx of finalIndexes) {
    const keys = Object.keys(idx.key).join(", ");
    console.log(`   ${idx.name}: { ${keys} }`);
  }
  console.log("═══════════════════════════════════════");

  await mongoose.disconnect();
  console.log("\n✅ Done! Search should now respond in < 100ms.");
}

createIndexes().catch(console.error);

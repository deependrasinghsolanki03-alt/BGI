#!/usr/bin/env node
// ─────────────────────────────────────────────────────
// pathfinder-backend/scripts/masterImport.js
//
// Global PBF Import Script — Streams .osm.pbf files
// into MongoDB using memory-efficient parsing.
//
// Usage:
//   node scripts/masterImport.js <path-to-file.osm.pbf> [options]
//
// Options:
//   --force       Clear existing OSM data before import
//   --no-nodes    Skip importing nodes
//   --no-ways     Skip importing ways
//   --no-rels     Skip importing relations
//   --batch=N     Batch size for bulkWrite (default: 10000)
//
// Download PBF files from:
//   - Geofabrik:  https://download.geofabrik.de/
//   - Planet OSM: https://planet.openstreetmap.org/
//   - BBBike:     https://extract.bbbike.org/
//
// Examples:
//   node scripts/masterImport.js india-latest.osm.pbf --force
//   node scripts/masterImport.js delhi.osm.pbf --batch=5000
//   node scripts/masterImport.js asia.osm.pbf --no-rels
// ─────────────────────────────────────────────────────

require("dotenv").config({ path: require("path").join(__dirname, "..", ".env") });
const fs = require("fs");
const path = require("path");
const mongoose = require("mongoose");
const { OsmNode, OsmWay, OsmRelation } = require("../models/OsmData");

// ═══════════════════════════════════════════════════
// Default PBF File Path
// Place your .osm.pbf file in the /data folder
// ═══════════════════════════════════════════════════
const DEFAULT_PBF_FILE = path.join(__dirname, "..", "data", "central-zone-latest.osm.pbf");

// ═══════════════════════════════════════════════════
// CLI Argument Parsing
// ═══════════════════════════════════════════════════
const args = process.argv.slice(2);
const cliPath = args.find((a) => !a.startsWith("--"));
const filePath = cliPath || DEFAULT_PBF_FILE;
const flags = {
  force: args.includes("--force"),
  skipNodes: args.includes("--no-nodes"),
  skipWays: args.includes("--no-ways"),
  skipRels: args.includes("--no-rels"),
  batchSize: parseInt(
    (args.find((a) => a.startsWith("--batch=")) || "--batch=10000").split("=")[1]
  ),
};

if (!cliPath && !fs.existsSync(DEFAULT_PBF_FILE)) {
  console.log(`
╔══════════════════════════════════════════════════════════╗
║          BGI Pathfinder — Global PBF Importer            ║
╚══════════════════════════════════════════════════════════╝

  ❌ Default PBF file not found:
     ${DEFAULT_PBF_FILE}

  Please either:
    1. Place your .osm.pbf file at:
       pathfinder-backend/data/central-zone-latest.osm.pbf

    2. Or specify a path as argument:
       node scripts/masterImport.js <file.osm.pbf> [options]

  Options:
    --force       Clear existing OSM data before import
    --no-nodes    Skip importing nodes
    --no-ways     Skip importing ways
    --no-rels     Skip importing relations
    --batch=N     Batch size (default: 10000)

  Download PBF from:
    https://download.geofabrik.de/asia/india.html

  Examples:
    node scripts/masterImport.js data/central-zone-latest.osm.pbf --force
    node scripts/masterImport.js delhi.osm.pbf --batch=5000
`);
  process.exit(1);
}

// ═══════════════════════════════════════════════════
// Progress Logger
// ═══════════════════════════════════════════════════
class ProgressLogger {
  constructor() {
    this.nodes = 0;
    this.ways = 0;
    this.relations = 0;
    this.nodeWrites = 0;
    this.wayWrites = 0;
    this.relWrites = 0;
    this.errors = 0;
    this.startTime = Date.now();
    this.lastLog = Date.now();
  }

  tick(type, count = 1) {
    this[type] += count;
    const now = Date.now();
    // Log every 5 seconds
    if (now - this.lastLog >= 5000) {
      this.print();
      this.lastLog = now;
    }
  }

  elapsed() {
    const sec = ((Date.now() - this.startTime) / 1000).toFixed(1);
    if (sec < 60) return `${sec}s`;
    const min = (sec / 60).toFixed(1);
    if (min < 60) return `${min}m`;
    const hr = (min / 60).toFixed(1);
    return `${hr}h`;
  }

  rate() {
    const sec = (Date.now() - this.startTime) / 1000;
    const total = this.nodes + this.ways + this.relations;
    return sec > 0 ? Math.round(total / sec) : 0;
  }

  print() {
    const mem = (process.memoryUsage().heapUsed / 1024 / 1024).toFixed(0);
    process.stdout.write(
      `\r  ⏱ ${this.elapsed()} | ` +
      `📌 Nodes: ${this.nodes.toLocaleString()} (${this.nodeWrites.toLocaleString()} written) | ` +
      `🛣️  Ways: ${this.ways.toLocaleString()} (${this.wayWrites.toLocaleString()} written) | ` +
      `🔗 Rels: ${this.relations.toLocaleString()} (${this.relWrites.toLocaleString()} written) | ` +
      `⚡ ${this.rate().toLocaleString()}/s | ` +
      `💾 ${mem}MB    `
    );
  }

  summary() {
    console.log(`\n
╔══════════════════════════════════════════════════════════╗
║                    Import Complete                        ║
╠══════════════════════════════════════════════════════════╣
║  📌 Nodes:     ${String(this.nodes.toLocaleString()).padEnd(40)}║
║  🛣️  Ways:      ${String(this.ways.toLocaleString()).padEnd(40)}║
║  🔗 Relations: ${String(this.relations.toLocaleString()).padEnd(40)}║
║  ✍️  Written:   N:${this.nodeWrites.toLocaleString()} W:${this.wayWrites.toLocaleString()} R:${this.relWrites.toLocaleString()}${" ".repeat(Math.max(0, 27 - (this.nodeWrites.toLocaleString().length + this.wayWrites.toLocaleString().length + this.relWrites.toLocaleString().length)))}║
║  ❌ Errors:    ${String(this.errors.toLocaleString()).padEnd(40)}║
║  ⏱ Time:      ${String(this.elapsed()).padEnd(40)}║
║  ⚡ Avg Rate:  ${String(this.rate().toLocaleString() + " items/sec").padEnd(40)}║
╚══════════════════════════════════════════════════════════╝`);
  }
}

// ═══════════════════════════════════════════════════
// Batch Writer — buffers ops and flushes via bulkWrite
// ═══════════════════════════════════════════════════
class BatchWriter {
  constructor(model, batchSize, progress, type) {
    this.model = model;
    this.batchSize = batchSize;
    this.buffer = [];
    this.progress = progress;
    this.type = type; // "nodeWrites", "wayWrites", "relWrites"
  }

  add(doc) {
    this.buffer.push({
      updateOne: {
        filter: doc.filter,
        update: { $set: doc.data },
        upsert: true,
      },
    });

    if (this.buffer.length >= this.batchSize) {
      return this.flush();
    }
    return Promise.resolve();
  }

  async flush() {
    if (this.buffer.length === 0) return;

    const ops = this.buffer.splice(0);
    try {
      const result = await this.model.bulkWrite(ops, { ordered: false });
      const written = (result.upsertedCount || 0) + (result.modifiedCount || 0);
      this.progress.tick(this.type, written);
    } catch (err) {
      // Duplicate key errors are OK (upsert handles them)
      if (err.code !== 11000) {
        this.progress.errors++;
      }
      // Count successful writes from partial failure
      if (err.result) {
        const written = (err.result.nUpserted || 0) + (err.result.nModified || 0);
        this.progress.tick(this.type, written);
      }
    }
  }
}

// ═══════════════════════════════════════════════════
// PBF Parser — using osm-read (robust callback-based)
// ═══════════════════════════════════════════════════
async function parsePBF(filePath, handlers) {
  let osmread;
  try {
    osmread = require("osm-read");
  } catch (e) {
    console.error("❌ osm-read not installed. Run:");
    console.error("   npm install osm-read");
    process.exit(1);
  }

  return new Promise((resolve, reject) => {
    osmread.parse({
      filePath: filePath,
      endDocument: () => {
        resolve();
      },
      node: (node) => {
        if (handlers.onNode) {
          handlers.onNode({
            id: node.id,
            lat: node.lat,
            lon: node.lon,
            tags: node.tags || {},
            type: "node",
          });
        }
      },
      way: (way) => {
        if (handlers.onWay) {
          handlers.onWay({
            id: way.id,
            refs: way.nodeRefs || [],
            tags: way.tags || {},
            type: "way",
          });
        }
      },
      relation: (relation) => {
        if (handlers.onRelation) {
          handlers.onRelation({
            id: relation.id,
            members: (relation.members || []).map((m) => ({
              type: m.type,
              id: m.id,
              role: m.role || "",
            })),
            tags: relation.tags || {},
            type: "relation",
          });
        }
      },
      error: (err) => {
        reject(err);
      },
    });
  });
}

// ═══════════════════════════════════════════════════
// Convert OSM tags object (clean up empty/null tags)
// ═══════════════════════════════════════════════════
function cleanTags(rawTags) {
  if (!rawTags || typeof rawTags !== "object") return {};
  const tags = {};
  for (const [key, val] of Object.entries(rawTags)) {
    if (val !== null && val !== undefined && val !== "") {
      tags[key] = val;
    }
  }
  return tags;
}

// ═══════════════════════════════════════════════════
// Main Import Function
// ═══════════════════════════════════════════════════
async function masterImport() {
  // Validate file exists
  if (!fs.existsSync(filePath)) {
    console.error(`❌ File not found: ${filePath}`);
    process.exit(1);
  }

  const fileSize = (fs.statSync(filePath).size / 1024 / 1024).toFixed(1);
  console.log(`
╔══════════════════════════════════════════════════════════╗
║          BGI Pathfinder — Global PBF Importer            ║
╚══════════════════════════════════════════════════════════╝

  📂 File:       ${filePath}
  📦 Size:       ${fileSize} MB
  🔧 Batch size: ${flags.batchSize.toLocaleString()}
  🗑️  Force:      ${flags.force}
  📌 Nodes:      ${flags.skipNodes ? "SKIPPED" : "IMPORTING"}
  🛣️  Ways:       ${flags.skipWays ? "SKIPPED" : "IMPORTING"}
  🔗 Relations:  ${flags.skipRels ? "SKIPPED" : "IMPORTING"}
`);

  // Connect to MongoDB
  console.log("  🔌 Connecting to MongoDB...");
  await mongoose.connect(process.env.MONGO_URI, {
    maxPoolSize: 20,  // More connections for parallel writes
  });
  console.log("  ✅ MongoDB connected\n");

  // Clear data if --force
  if (flags.force) {
    console.log("  🗑️  Clearing existing OSM data...");
    const delResults = await Promise.all([
      !flags.skipNodes ? OsmNode.deleteMany({}) : Promise.resolve({ deletedCount: 0 }),
      !flags.skipWays ? OsmWay.deleteMany({}) : Promise.resolve({ deletedCount: 0 }),
      !flags.skipRels ? OsmRelation.deleteMany({}) : Promise.resolve({ deletedCount: 0 }),
    ]);
    console.log(
      `  ✅ Cleared: ${delResults[0].deletedCount} nodes, ` +
      `${delResults[1].deletedCount} ways, ` +
      `${delResults[2].deletedCount} relations\n`
    );
  }

  // Setup progress + batch writers
  const progress = new ProgressLogger();

  const nodeWriter = new BatchWriter(OsmNode, flags.batchSize, progress, "nodeWrites");
  const wayWriter = new BatchWriter(OsmWay, flags.batchSize, progress, "wayWrites");
  const relWriter = new BatchWriter(OsmRelation, flags.batchSize, progress, "relWrites");

  // Pending flush promises
  const pendingFlushes = [];

  console.log("  🚀 Parsing PBF stream...\n");

  // Parse PBF file
  await parsePBF(filePath, {
    onNode: flags.skipNodes
      ? null
      : (item) => {
          progress.tick("nodes");

          // Skip nodes without coordinates
          if (item.lat === undefined || item.lon === undefined) return;

          const p = nodeWriter.add({
            filter: { nodeId: item.id },
            data: {
              nodeId: item.id,
              location: {
                type: "Point",
                coordinates: [item.lon, item.lat],
              },
              tags: cleanTags(item.tags),
            },
          });
          if (p && p.then) pendingFlushes.push(p);
        },

    onWay: flags.skipWays
      ? null
      : (item) => {
          progress.tick("ways");

          // Skip ways without node references
          if (!item.refs || item.refs.length === 0) return;

          const p = wayWriter.add({
            filter: { wayId: item.id },
            data: {
              wayId: item.id,
              nodeRefs: item.refs,
              tags: cleanTags(item.tags),
            },
          });
          if (p && p.then) pendingFlushes.push(p);
        },

    onRelation: flags.skipRels
      ? null
      : (item) => {
          progress.tick("relations");

          if (!item.members || item.members.length === 0) return;

          const members = item.members.map((m) => ({
            type: m.type,
            ref: m.id,
            role: m.role || "",
          }));

          const p = relWriter.add({
            filter: { relationId: item.id },
            data: {
              relationId: item.id,
              members,
              tags: cleanTags(item.tags),
            },
          });
          if (p && p.then) pendingFlushes.push(p);
        },
  });

  // Flush remaining buffers
  console.log("\n\n  💾 Flushing remaining buffers...");
  await Promise.all([
    nodeWriter.flush(),
    wayWriter.flush(),
    relWriter.flush(),
    ...pendingFlushes,
  ]);

  // Final progress
  progress.print();
  progress.summary();

  // Create indexes
  await createIndexes();

  // Disconnect
  await mongoose.disconnect();
  console.log("\n  🔌 Disconnected from MongoDB");
}

// ═══════════════════════════════════════════════════
// Create all indexes for fast queries
// ═══════════════════════════════════════════════════
async function createIndexes() {
  console.log("\n  📐 Creating indexes...");

  try {
    if (!flags.skipNodes) {
      await OsmNode.collection.createIndex({ location: "2dsphere" });
      console.log("     ✅ OsmNode: 2dsphere (location)");

      await OsmNode.collection.createIndex({ nodeId: 1 }, { unique: true });
      console.log("     ✅ OsmNode: unique (nodeId)");

      await OsmNode.collection.createIndex({ "tags.amenity": 1 });
      console.log("     ✅ OsmNode: tag index (amenity)");

      await OsmNode.collection.createIndex({ "tags.shop": 1 });
      console.log("     ✅ OsmNode: tag index (shop)");
    }

    if (!flags.skipWays) {
      await OsmWay.collection.createIndex({ wayId: 1 }, { unique: true });
      console.log("     ✅ OsmWay: unique (wayId)");

      await OsmWay.collection.createIndex({ nodeRefs: 1 });
      console.log("     ✅ OsmWay: multikey (nodeRefs)");

      await OsmWay.collection.createIndex({ "tags.highway": 1 });
      console.log("     ✅ OsmWay: tag index (highway)");

      await OsmWay.collection.createIndex({ "tags.building": 1 });
      console.log("     ✅ OsmWay: tag index (building)");
    }

    if (!flags.skipRels) {
      await OsmRelation.collection.createIndex({ relationId: 1 }, { unique: true });
      console.log("     ✅ OsmRelation: unique (relationId)");

      await OsmRelation.collection.createIndex({ "tags.type": 1 });
      console.log("     ✅ OsmRelation: tag index (type)");

      await OsmRelation.collection.createIndex({ "members.ref": 1 });
      console.log("     ✅ OsmRelation: member ref index");
    }

    console.log("     ✅ All indexes created!");
  } catch (err) {
    console.warn("     ⚠️ Some indexes may already exist:", err.message);
  }
}

// ═══════════════════════════════════════════════════
// Run
// ═══════════════════════════════════════════════════
masterImport().catch((err) => {
  console.error("\n❌ Import failed:", err.message);
  console.error(err.stack);
  process.exit(1);
});

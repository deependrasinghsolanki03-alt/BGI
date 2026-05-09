// ─────────────────────────────────────────────────────
// pathfinder-backend/models/OsmData.js
// Raw OSM data models: OsmNode, OsmWay, OsmRelation
//
// These store the COMPLETE OSM dataset (every node, way,
// relation) separate from the routing-specific MapNode/MapEdge.
// ─────────────────────────────────────────────────────
const mongoose = require("mongoose");

// ═══════════════════════════════════════════════════
// OsmNode — every coordinate point in the PBF file
// Trees, shops, traffic lights, intersections, etc.
// ═══════════════════════════════════════════════════
const osmNodeSchema = new mongoose.Schema(
  {
    nodeId: {
      type: Number,
      required: true,
      unique: true,
      index: true,
    },
    location: {
      type: {
        type: String,
        enum: ["Point"],
        default: "Point",
      },
      coordinates: {
        type: [Number], // [longitude, latitude]
        required: true,
      },
    },
    tags: {
      type: mongoose.Schema.Types.Mixed,
      default: {},
    },
  },
  {
    timestamps: false, // No timestamps for raw data (saves ~20% storage)
    versionKey: false,
  }
);

// 2dsphere for spatial queries
osmNodeSchema.index({ location: "2dsphere" });
// Search indexes — for local geocoding (replaces Nominatim)
osmNodeSchema.index({ "tags.name": 1 });
osmNodeSchema.index({ "tags.amenity": 1 });
osmNodeSchema.index({ "tags.shop": 1 });
osmNodeSchema.index({ "tags.place": 1 });

// ═══════════════════════════════════════════════════
// OsmWay — roads, buildings, boundaries, rivers, etc.
// Contains ordered array of node references
// ═══════════════════════════════════════════════════
const osmWaySchema = new mongoose.Schema(
  {
    wayId: {
      type: Number,
      required: true,
      unique: true,
      index: true,
    },
    nodeRefs: {
      type: [Number], // Ordered array of OsmNode.nodeId
      required: true,
    },
    tags: {
      type: mongoose.Schema.Types.Mixed,
      default: {},
    },
  },
  {
    timestamps: false,
    versionKey: false,
  }
);

// Index for finding ways that contain a specific node
osmWaySchema.index({ nodeRefs: 1 });
// Index for highway tag lookups (routing queries)
osmWaySchema.index({ "tags.highway": 1 });
// Search index — for local road name search
osmWaySchema.index({ "tags.name": 1 });

// ═══════════════════════════════════════════════════
// OsmRelation — complex OSM structures
// Bus routes, multipolygons, boundaries, turn restrictions
// ═══════════════════════════════════════════════════
const osmRelationSchema = new mongoose.Schema(
  {
    relationId: {
      type: Number,
      required: true,
      unique: true,
      index: true,
    },
    members: [
      {
        type: {
          type: String, // "node", "way", "relation"
          required: true,
        },
        ref: {
          type: Number, // ID of the referenced element
          required: true,
        },
        role: {
          type: String, // "outer", "inner", "stop", "platform", etc.
          default: "",
        },
      },
    ],
    tags: {
      type: mongoose.Schema.Types.Mixed,
      default: {},
    },
  },
  {
    timestamps: false,
    versionKey: false,
  }
);

// Index for finding relations by type
osmRelationSchema.index({ "tags.type": 1 });

const OsmNode = mongoose.model("OsmNode", osmNodeSchema);
const OsmWay = mongoose.model("OsmWay", osmWaySchema);
const OsmRelation = mongoose.model("OsmRelation", osmRelationSchema);

module.exports = { OsmNode, OsmWay, OsmRelation };

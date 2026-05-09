// ─────────────────────────────────────────────────────
// pathfinder-backend/models/MapData.js
// Mongoose Schemas: MapNode (GeoJSON) + MapEdge
// ─────────────────────────────────────────────────────
const mongoose = require("mongoose");

// ═══════════════════════════════════════════════════
// MapNode Schema — represents an intersection / point
// Uses GeoJSON Point for spatial indexing
// ═══════════════════════════════════════════════════
const mapNodeSchema = new mongoose.Schema(
  {
    // Original OSM node ID (stored as string for compatibility)
    osmId: {
      type: String,
      required: true,
      unique: true,
      index: true,
    },

    // GeoJSON Point — enables $nearSphere, $geoWithin, 2dsphere index
    location: {
      type: {
        type: String,
        enum: ["Point"],
        required: true,
        default: "Point",
      },
      coordinates: {
        type: [Number], // [longitude, latitude] — GeoJSON order!
        required: true,
      },
    },

    // Optional metadata
    name: { type: String, default: "" },
    tags: { type: mongoose.Schema.Types.Mixed, default: {} },
  },
  {
    timestamps: true,
    toJSON: { virtuals: true },
  }
);

// 2dsphere index for geospatial queries
mapNodeSchema.index({ location: "2dsphere" });

// Virtual getters for convenience
mapNodeSchema.virtual("lat").get(function () {
  return this.location.coordinates[1];
});
mapNodeSchema.virtual("lng").get(function () {
  return this.location.coordinates[0];
});

// ═══════════════════════════════════════════════════
// MapEdge Schema — represents a road segment
// Links two MapNodes by their osmId
// ═══════════════════════════════════════════════════
const mapEdgeSchema = new mongoose.Schema(
  {
    // References to MapNode osmIds
    startNode: {
      type: String,
      required: true,
      index: true,
    },
    endNode: {
      type: String,
      required: true,
      index: true,
    },

    // Distance in meters (Haversine-calculated)
    distance: {
      type: Number,
      required: true,
    },

    // Road properties
    roadType: {
      type: String,
      default: "unclassified",
      enum: [
        "motorway", "motorway_link",
        "trunk", "trunk_link",
        "primary", "primary_link",
        "secondary", "secondary_link",
        "tertiary", "tertiary_link",
        "residential", "unclassified",
      ],
    },

    // Cost multipliers for weighted A*
    trafficMultiplier: { type: Number, default: 1.0 },
    roadQualityMultiplier: { type: Number, default: 1.0 },

    // Average speed on this segment (km/h)
    speedKmh: { type: Number, default: 25.0 },

    // One-way flag
    isOneWay: { type: Boolean, default: false },

    // Original OSM way ID
    osmWayId: { type: String, default: "" },

    // Road name
    name: { type: String, default: "" },
  },
  {
    timestamps: true,
  }
);

// Compound index for fast edge lookups
mapEdgeSchema.index({ startNode: 1, endNode: 1 });

const MapNode = mongoose.model("MapNode", mapNodeSchema);
const MapEdge = mongoose.model("MapEdge", mapEdgeSchema);

module.exports = { MapNode, MapEdge };

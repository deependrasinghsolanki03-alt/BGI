// app/src/main/java/com/bgi/pathfinder/proto/MapProto.java
//
// ────────────────────────────────────────────────────────────
// Hand-written Protobuf message classes matching map.proto
//
// These mirror the backend's proto/map.proto schema:
//   - Node, Edge, LatLng, BoundingBox, GraphStats, MapGraph
//
// Uses protobuf-javalite for decoding binary data.
// To regenerate from .proto:
//   protoc --java_out=lite:app/src/main/java proto/map.proto
// ────────────────────────────────────────────────────────────

package com.bgi.pathfinder.proto;

import com.google.protobuf.CodedInputStream;
import com.google.protobuf.InvalidProtocolBufferException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class MapProto {

    private MapProto() {}

    // ═══════════════════════════════════════
    // Node (field tags from map.proto)
    // ═══════════════════════════════════════
    public static final class Node {
        private final String id;          // tag 1
        private final double lat;         // tag 2
        private final double lng;         // tag 3
        private final String name;        // tag 4

        private Node(String id, double lat, double lng, String name) {
            this.id = id;
            this.lat = lat;
            this.lng = lng;
            this.name = name;
        }

        public String getId() { return id; }
        public double getLat() { return lat; }
        public double getLng() { return lng; }
        public String getName() { return name; }

        static Node parseFrom(CodedInputStream input) throws IOException {
            String id = "";
            double lat = 0, lng = 0;
            String name = "";
            while (!input.isAtEnd()) {
                int tag = input.readTag();
                switch (tag >>> 3) {
                    case 1: id = input.readString(); break;
                    case 2: lat = input.readDouble(); break;
                    case 3: lng = input.readDouble(); break;
                    case 4: name = input.readString(); break;
                    default: input.skipField(tag); break;
                }
            }
            return new Node(id, lat, lng, name);
        }
    }

    // ═══════════════════════════════════════
    // Edge
    // ═══════════════════════════════════════
    public static final class Edge {
        private final String id;                  // tag 1
        private final String startNode;           // tag 2
        private final String endNode;             // tag 3
        private final double distance;            // tag 4
        private final double trafficMultiplier;   // tag 5
        private final double qualityMultiplier;   // tag 6
        private final boolean isOneWay;           // tag 7
        private final String roadType;            // tag 8
        private final double speedKmh;            // tag 9

        private Edge(String id, String startNode, String endNode, double distance,
                     double trafficMultiplier, double qualityMultiplier,
                     boolean isOneWay, String roadType, double speedKmh) {
            this.id = id;
            this.startNode = startNode;
            this.endNode = endNode;
            this.distance = distance;
            this.trafficMultiplier = trafficMultiplier;
            this.qualityMultiplier = qualityMultiplier;
            this.isOneWay = isOneWay;
            this.roadType = roadType;
            this.speedKmh = speedKmh;
        }

        public String getId() { return id; }
        public String getStartNode() { return startNode; }
        public String getEndNode() { return endNode; }
        public double getDistance() { return distance; }
        public double getTrafficMultiplier() { return trafficMultiplier; }
        public double getQualityMultiplier() { return qualityMultiplier; }
        public boolean getIsOneWay() { return isOneWay; }
        public String getRoadType() { return roadType; }
        public double getSpeedKmh() { return speedKmh; }

        static Edge parseFrom(CodedInputStream input) throws IOException {
            String id = "", startNode = "", endNode = "", roadType = "";
            double distance = 0, trafficMul = 1.0, qualityMul = 1.0, speed = 25.0;
            boolean oneWay = false;
            while (!input.isAtEnd()) {
                int tag = input.readTag();
                switch (tag >>> 3) {
                    case 1: id = input.readString(); break;
                    case 2: startNode = input.readString(); break;
                    case 3: endNode = input.readString(); break;
                    case 4: distance = input.readDouble(); break;
                    case 5: trafficMul = input.readDouble(); break;
                    case 6: qualityMul = input.readDouble(); break;
                    case 7: oneWay = input.readBool(); break;
                    case 8: roadType = input.readString(); break;
                    case 9: speed = input.readDouble(); break;
                    case 10: // geometry — skip for now (repeated LatLng)
                        input.skipField(tag);
                        break;
                    default: input.skipField(tag); break;
                }
            }
            return new Edge(id, startNode, endNode, distance,
                           trafficMul, qualityMul, oneWay, roadType, speed);
        }
    }

    // ═══════════════════════════════════════
    // GraphStats
    // ═══════════════════════════════════════
    public static final class GraphStats {
        private final int nodeCount;         // tag 1
        private final int edgeCount;         // tag 2
        private final double bboxAreaKm2;    // tag 3
        private final long queryTimeMs;      // tag 4
        private final String format;         // tag 5

        private GraphStats(int nodeCount, int edgeCount, double bboxAreaKm2,
                          long queryTimeMs, String format) {
            this.nodeCount = nodeCount;
            this.edgeCount = edgeCount;
            this.bboxAreaKm2 = bboxAreaKm2;
            this.queryTimeMs = queryTimeMs;
            this.format = format;
        }

        public int getNodeCount() { return nodeCount; }
        public int getEdgeCount() { return edgeCount; }
        public double getBboxAreaKm2() { return bboxAreaKm2; }
        public long getQueryTimeMs() { return queryTimeMs; }
        public String getFormat() { return format; }

        static GraphStats parseFrom(CodedInputStream input) throws IOException {
            int nodes = 0, edges = 0;
            double area = 0;
            long queryTime = 0;
            String format = "";
            while (!input.isAtEnd()) {
                int tag = input.readTag();
                switch (tag >>> 3) {
                    case 1: nodes = input.readInt32(); break;
                    case 2: edges = input.readInt32(); break;
                    case 3: area = input.readDouble(); break;
                    case 4: queryTime = input.readInt64(); break;
                    case 5: format = input.readString(); break;
                    default: input.skipField(tag); break;
                }
            }
            return new GraphStats(nodes, edges, area, queryTime, format);
        }
    }

    // ═══════════════════════════════════════
    // MapGraph — top-level message
    // ═══════════════════════════════════════
    public static final class MapGraph {
        private final List<Node> nodes;      // tag 1 (repeated)
        private final List<Edge> edges;      // tag 2 (repeated)
        private final GraphStats stats;      // tag 4

        private MapGraph(List<Node> nodes, List<Edge> edges, GraphStats stats) {
            this.nodes = Collections.unmodifiableList(nodes);
            this.edges = Collections.unmodifiableList(edges);
            this.stats = stats;
        }

        public List<Node> getNodesList() { return nodes; }
        public List<Edge> getEdgesList() { return edges; }
        public GraphStats getStats() { return stats; }
        public boolean hasStats() { return stats != null; }

        /**
         * Decode a Protobuf binary byte array into a MapGraph.
         *
         * This is the main entry point for Android:
         *   byte[] bytes = response.body().bytes();
         *   MapGraph graph = MapGraph.parseFrom(bytes);
         *
         * @param data Raw protobuf bytes (gzip already decompressed by OkHttp)
         * @return Decoded MapGraph with nodes, edges, and stats
         */
        public static MapGraph parseFrom(byte[] data)
                throws InvalidProtocolBufferException {
            try {
                CodedInputStream input = CodedInputStream.newInstance(data);
                // Allow large messages (up to 64MB)
                input.setSizeLimit(64 * 1024 * 1024);

                List<Node> nodes = new ArrayList<>();
                List<Edge> edges = new ArrayList<>();
                GraphStats stats = null;

                while (!input.isAtEnd()) {
                    int tag = input.readTag();
                    switch (tag >>> 3) {
                        case 1: { // Node (length-delimited)
                            int length = input.readRawVarint32();
                            int oldLimit = input.pushLimit(length);
                            nodes.add(Node.parseFrom(input));
                            input.popLimit(oldLimit);
                            break;
                        }
                        case 2: { // Edge (length-delimited)
                            int length = input.readRawVarint32();
                            int oldLimit = input.pushLimit(length);
                            edges.add(Edge.parseFrom(input));
                            input.popLimit(oldLimit);
                            break;
                        }
                        case 3: { // BoundingBox — skip for now
                            input.skipField(tag);
                            break;
                        }
                        case 4: { // GraphStats (length-delimited)
                            int length = input.readRawVarint32();
                            int oldLimit = input.pushLimit(length);
                            stats = GraphStats.parseFrom(input);
                            input.popLimit(oldLimit);
                            break;
                        }
                        default:
                            input.skipField(tag);
                            break;
                    }
                }

                return new MapGraph(nodes, edges, stats);
            } catch (IOException e) {
                throw new InvalidProtocolBufferException(e.getMessage());
            }
        }
    }
}

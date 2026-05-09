// ============================================================
// FILE PATH in Android Studio:
// app/src/main/java/com/bgi/pathfinder/utils/GraphBuilder.kt
// ============================================================
package com.bgi.pathfinder.utils

import com.bgi.pathfinder.models.*

/**
 * Utility class to build a Graph from real-world coordinate data.
 *
 * HOW TO INITIALIZE GRAPH DATA:
 * ──────────────────────────────
 * In a real application, graph data (nodes and edges) can come from:
 *
 * 1. OPENSTREETMAP (OSM) DATA:
 *    - Download .osm/.pbf files from https://download.geofabrik.de/
 *    - Parse XML/PBF to extract intersections (nodes) and roads (ways/edges)
 *    - Libraries: Osmosis, osm4j, or custom XML parsing
 *
 * 2. OVERPASS API (live OSM queries):
 *    - Query: [out:json];way["highway"](bbox);(._;>;);out body;
 *    - Returns roads within a bounding box with all node coordinates
 *    - Great for dynamically loading graph data for a specific area
 *
 * 3. OSRM (OpenStreetMap Routing Machine):
 *    - Use as a fallback/comparison — OSRM can compute routes via its API
 *    - But our A* runs locally, giving full control over weight parameters
 *
 * 4. HARDCODED / LOCAL JSON:
 *    - For demos or campus/building maps, define nodes and edges manually
 *    - Store as JSON in assets/ folder and parse at runtime
 *
 * This file provides:
 *  - buildSampleGraph(): A demo graph for testing in Delhi, India
 *  - buildGraphFromJson(): Template for loading from JSON assets
 *  - Helper functions for creating edges between nodes
 */
object GraphBuilder {

    /**
     * Build a sample graph for demonstration purposes.
     * Uses real coordinates from central Delhi landmarks.
     *
     * This creates a small network of ~10 nodes with varying
     * traffic and road quality weights for testing both A* modes.
     */
    fun buildSampleGraph(): Graph {
        val graph = Graph()

        // ── Define Nodes (intersections / landmarks) ──
        val nodes = listOf(
            Node("N1", 28.6139, 77.2090, "Connaught Place"),
            Node("N2", 28.6280, 77.2189, "Civil Lines"),
            Node("N3", 28.6353, 77.2250, "Vidhan Sabha"),
            Node("N4", 28.6129, 77.2295, "India Gate"),
            Node("N5", 28.5933, 77.2507, "Humayun's Tomb"),
            Node("N6", 28.6127, 77.2773, "Akshardham"),
            Node("N7", 28.5562, 77.2510, "Nehru Place"),
            Node("N8", 28.5921, 77.2182, "Lodhi Garden"),
            Node("N9", 28.6304, 77.2177, "Old Delhi Railway"),
            Node("N10", 28.6508, 77.2334, "ISBT Kashmere Gate")
        )

        nodes.forEach { graph.addNode(it) }

        // ── Define Edges (road segments) ──
        // Each edge has: distance (auto-calculated), traffic multiplier, road quality multiplier

        // CP ↔ Civil Lines (normal traffic, good road)
        graph.addEdge(createEdge(nodes, "N1", "N2", traffic = 1.2, quality = 1.0))
        // Civil Lines ↔ Vidhan Sabha (light traffic, good road)
        graph.addEdge(createEdge(nodes, "N2", "N3", traffic = 1.0, quality = 1.0))
        // CP ↔ India Gate (heavy traffic, excellent road)
        graph.addEdge(createEdge(nodes, "N1", "N4", traffic = 2.0, quality = 0.8))
        // India Gate ↔ Humayun's Tomb (moderate traffic, decent road)
        graph.addEdge(createEdge(nodes, "N4", "N5", traffic = 1.5, quality = 1.2))
        // Humayun's Tomb ↔ Akshardham (light traffic, good road)
        graph.addEdge(createEdge(nodes, "N5", "N6", traffic = 1.1, quality = 1.0))
        // India Gate ↔ Akshardham (moderate traffic, average road)
        graph.addEdge(createEdge(nodes, "N4", "N6", traffic = 1.8, quality = 1.3))
        // Humayun's Tomb ↔ Nehru Place (heavy traffic, poor road)
        graph.addEdge(createEdge(nodes, "N5", "N7", traffic = 2.5, quality = 1.8))
        // CP ↔ Lodhi Garden (moderate traffic, good road)
        graph.addEdge(createEdge(nodes, "N1", "N8", traffic = 1.4, quality = 1.0))
        // Lodhi Garden ↔ Humayun's Tomb (light traffic, excellent road)
        graph.addEdge(createEdge(nodes, "N8", "N5", traffic = 1.0, quality = 0.9))
        // Lodhi Garden ↔ Nehru Place (moderate traffic, decent road)
        graph.addEdge(createEdge(nodes, "N8", "N7", traffic = 1.6, quality = 1.1))
        // Civil Lines ↔ Old Delhi Railway (heavy traffic, poor road)
        graph.addEdge(createEdge(nodes, "N2", "N9", traffic = 2.2, quality = 1.7))
        // Vidhan Sabha ↔ ISBT (moderate traffic, average road)
        graph.addEdge(createEdge(nodes, "N3", "N10", traffic = 1.5, quality = 1.3))
        // Old Delhi Railway ↔ ISBT (heavy traffic, poor road)
        graph.addEdge(createEdge(nodes, "N9", "N10", traffic = 2.0, quality = 1.5))
        // CP ↔ Old Delhi Railway (extreme traffic, decent road)
        graph.addEdge(createEdge(nodes, "N1", "N9", traffic = 2.8, quality = 1.2))
        // Akshardham ↔ Nehru Place (moderate traffic, good road)
        graph.addEdge(createEdge(nodes, "N6", "N7", traffic = 1.3, quality = 1.0))
        // India Gate ↔ Lodhi Garden (light traffic, good road)
        graph.addEdge(createEdge(nodes, "N4", "N8", traffic = 1.1, quality = 1.0))

        return graph
    }

    /**
     * Create an Edge between two nodes, auto-calculating distance via Haversine.
     *
     * @param nodes   The list of all nodes (used to look up lat/lng)
     * @param fromId  Source node ID
     * @param toId    Destination node ID
     * @param traffic Traffic congestion multiplier (1.0 = free flow)
     * @param quality Road quality multiplier (1.0 = perfect road, >1.0 = worse)
     * @param oneWay  Whether the road is one-way
     */
    private fun createEdge(
        nodes: List<Node>,
        fromId: String,
        toId: String,
        traffic: Double = 1.0,
        quality: Double = 1.0,
        oneWay: Boolean = false
    ): Edge {
        val from = nodes.first { it.id == fromId }
        val to = nodes.first { it.id == toId }
        val distance = Graph.haversineDistance(from.lat, from.lng, to.lat, to.lng)

        return Edge(
            from = fromId,
            to = toId,
            distance = distance,
            trafficMultiplier = traffic,
            roadQualityMultiplier = quality,
            isOneWay = oneWay
        )
    }

    /**
     * TEMPLATE: Build a graph from a JSON file stored in assets/.
     *
     * Expected JSON format:
     * {
     *   "nodes": [
     *     { "id": "N1", "lat": 28.6139, "lng": 77.2090, "name": "Place A" },
     *     ...
     *   ],
     *   "edges": [
     *     { "from": "N1", "to": "N2", "traffic": 1.5, "quality": 1.2, "oneWay": false },
     *     ...
     *   ]
     * }
     *
     * Usage in Activity:
     *   val json = assets.open("graph_data.json").bufferedReader().readText()
     *   val graph = GraphBuilder.buildGraphFromJson(json)
     */
    fun buildGraphFromJson(json: String): Graph {
        val graph = Graph()

        // Parse using org.json (available in Android without extra deps)
        val jsonObj = org.json.JSONObject(json)

        // Parse nodes
        val nodesArray = jsonObj.getJSONArray("nodes")
        val nodesList = mutableListOf<Node>()
        for (i in 0 until nodesArray.length()) {
            val nodeJson = nodesArray.getJSONObject(i)
            val node = Node(
                id = nodeJson.getString("id"),
                lat = nodeJson.getDouble("lat"),
                lng = nodeJson.getDouble("lng"),
                name = nodeJson.optString("name", "")
            )
            graph.addNode(node)
            nodesList.add(node)
        }

        // Parse edges
        val edgesArray = jsonObj.getJSONArray("edges")
        for (i in 0 until edgesArray.length()) {
            val edgeJson = edgesArray.getJSONObject(i)
            val fromId = edgeJson.getString("from")
            val toId = edgeJson.getString("to")
            val traffic = edgeJson.optDouble("traffic", 1.0)
            val quality = edgeJson.optDouble("quality", 1.0)
            val oneWay = edgeJson.optBoolean("oneWay", false)

            val edge = createEdge(nodesList, fromId, toId, traffic, quality, oneWay)
            graph.addEdge(edge)
        }

        return graph
    }
}

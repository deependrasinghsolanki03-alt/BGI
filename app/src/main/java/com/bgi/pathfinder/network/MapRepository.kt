// app/src/main/java/com/bgi/pathfinder/network/MapRepository.kt
package com.bgi.pathfinder.network

import android.util.Log
import com.bgi.pathfinder.models.*
import com.bgi.pathfinder.proto.MapProto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * MapRepository — single source of truth for road network data.
 *
 * 100% LOCAL — fetches ONLY from our Node.js + MongoDB backend.
 * NO Nominatim, NO Overpass, NO external APIs.
 *
 * Fallback chain (all local):
 *   1. Protobuf binary → /api/v1/stream-map (~90% smaller)
 *   2. JSON            → /api/map/route-graph (debuggable)
 *
 * Works offline on hotspot (phone ↔ laptop).
 */
class MapRepository {

    private val api = RetrofitClient.api
    private val TAG = "MapRepository"

    /**
     * Result wrapper with metadata
     */
    data class GraphResult(
        val graph: Graph,
        val source: String,        // "protobuf" or "json"
        val sizeBytes: Int,        // Response size in bytes
        val decodeTimeMs: Long     // Time to decode the response
    )

    // ═══════════════════════════════════════════════
    // Fetch route graph — LOCAL ONLY
    // ═══════════════════════════════════════════════

    /**
     * Fetch road network graph with local fallback:
     *   Protobuf → JSON (both from local MongoDB)
     */
    suspend fun fetchRouteGraph(
        startLat: Double, startLng: Double,
        endLat: Double, endLng: Double,
        padding: Double = 1.5
    ): GraphResult = withContext(Dispatchers.IO) {

        // Attempt 1: Protobuf binary stream (fastest, ~90% smaller)
        try {
            val result = fetchProtobuf(startLat, startLng, endLat, endLng, padding)
            if (!result.graph.isEmpty()) {
                Log.d(TAG, "✅ Protobuf: ${result.graph.nodeCount()} nodes, " +
                    "${result.sizeBytes} bytes, ${result.decodeTimeMs}ms")
                return@withContext result
            }
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Protobuf failed, trying JSON: ${e.message}")
        }

        // Attempt 2: JSON fallback (same local data, different format)
        try {
            val result = fetchJson(startLat, startLng, endLat, endLng, padding)
            if (!result.graph.isEmpty()) {
                Log.d(TAG, "✅ JSON: ${result.graph.nodeCount()} nodes")
                return@withContext result
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ JSON also failed: ${e.message}")
        }

        // Both local endpoints failed
        throw Exception("Backend unreachable. Ensure laptop is on same hotspot and server is running.")
    }

    // ═══════════════════════════════════════════════
    // Protobuf decoder (local MongoDB data)
    // ═══════════════════════════════════════════════

    private suspend fun fetchProtobuf(
        startLat: Double, startLng: Double,
        endLat: Double, endLng: Double,
        padding: Double
    ): GraphResult {
        val response = api.streamMapGraph(startLat, startLng, endLat, endLng, padding)

        if (!response.isSuccessful) {
            throw Exception("Stream API error: ${response.code()}")
        }

        val body = response.body() ?: throw Exception("Empty protobuf response")
        val bytes = body.bytes()
        val decodeStart = System.currentTimeMillis()

        val mapGraph = MapProto.MapGraph.parseFrom(bytes)
        val graph = Graph()

        for (protoNode in mapGraph.nodesList) {
            graph.addNode(Node(
                id = protoNode.id,
                lat = protoNode.lat,
                lng = protoNode.lng,
                name = protoNode.name
            ))
        }

        for (protoEdge in mapGraph.edgesList) {
            graph.addEdge(Edge(
                from = protoEdge.startNode,
                to = protoEdge.endNode,
                distance = protoEdge.distance,
                trafficMultiplier = protoEdge.trafficMultiplier,
                roadQualityMultiplier = protoEdge.qualityMultiplier,
                isOneWay = protoEdge.isOneWay,
                roadType = protoEdge.roadType,
                speedKmh = protoEdge.speedKmh
            ))
        }

        val decodeTimeMs = System.currentTimeMillis() - decodeStart

        if (mapGraph.hasStats()) {
            val stats = mapGraph.stats
            Log.d(TAG, "📦 Proto stats: ${stats.nodeCount} nodes, " +
                "${stats.edgeCount} edges, ${stats.bboxAreaKm2} km², " +
                "server query: ${stats.queryTimeMs}ms")
        }

        return GraphResult(graph, "protobuf", bytes.size, decodeTimeMs)
    }

    // ═══════════════════════════════════════════════
    // JSON decoder (local MongoDB data)
    // ═══════════════════════════════════════════════

    private suspend fun fetchJson(
        startLat: Double, startLng: Double,
        endLat: Double, endLng: Double,
        padding: Double
    ): GraphResult {
        val response = api.getRouteGraphJson(startLat, startLng, endLat, endLng, padding)

        if (!response.isSuccessful) {
            throw Exception("JSON API error: ${response.code()}")
        }

        val body = response.body() ?: throw Exception("Empty JSON response")
        val jsonStr = body.string()
        val decodeStart = System.currentTimeMillis()

        val json = JSONObject(jsonStr)
        val graph = Graph()

        val nodesArray = json.getJSONArray("nodes")
        for (i in 0 until nodesArray.length()) {
            val n = nodesArray.getJSONObject(i)
            graph.addNode(Node(
                id = n.getString("id"),
                lat = n.getDouble("lat"),
                lng = n.getDouble("lng"),
                name = n.optString("name", "")
            ))
        }

        val edgesArray = json.getJSONArray("edges")
        for (i in 0 until edgesArray.length()) {
            val e = edgesArray.getJSONObject(i)
            graph.addEdge(Edge(
                from = e.getString("from"),
                to = e.getString("to"),
                distance = e.getDouble("distance"),
                trafficMultiplier = e.optDouble("trafficMultiplier", 1.0),
                roadQualityMultiplier = e.optDouble("roadQualityMultiplier", 1.0),
                isOneWay = e.optBoolean("isOneWay", false),
                roadType = e.optString("roadType", ""),
                speedKmh = e.optDouble("speedKmh", 25.0)
            ))
        }

        val decodeTimeMs = System.currentTimeMillis() - decodeStart
        return GraphResult(graph, "json", jsonStr.length, decodeTimeMs)
    }

    // ═══════════════════════════════════════════════
    // BBox Graph — For Hybrid Map Overlay
    // ═══════════════════════════════════════════════

    /**
     * Fetch road network for a visible map viewport.
     * Used by HybridMapOverlay for live vector rendering.
     *
     * Uses the stream-map endpoint with SW/NE corners as start/end,
     * which triggers the LOD system on the backend.
     *
     * Returns null on failure (non-critical — tiles still show).
     */
    suspend fun fetchBBoxGraph(
        south: Double, west: Double,
        north: Double, east: Double
    ): GraphResult? = withContext(Dispatchers.IO) {

        // Attempt 1: Protobuf via stream-map (fastest)
        try {
            val response = api.streamMapBBox(
                startLat = south, startLng = west,
                endLat = north, endLng = east
            )
            if (response.isSuccessful) {
                val body = response.body()
                if (body != null) {
                    val bytes = body.bytes()
                    if (bytes.isNotEmpty()) {
                        val decodeStart = System.currentTimeMillis()
                        val mapGraph = MapProto.MapGraph.parseFrom(bytes)
                        val graph = Graph()

                        for (protoNode in mapGraph.nodesList) {
                            graph.addNode(Node(
                                id = protoNode.id,
                                lat = protoNode.lat,
                                lng = protoNode.lng,
                                name = protoNode.name
                            ))
                        }
                        for (protoEdge in mapGraph.edgesList) {
                            graph.addEdge(Edge(
                                from = protoEdge.startNode,
                                to = protoEdge.endNode,
                                distance = protoEdge.distance,
                                trafficMultiplier = protoEdge.trafficMultiplier,
                                roadQualityMultiplier = protoEdge.qualityMultiplier,
                                isOneWay = protoEdge.isOneWay,
                                roadType = protoEdge.roadType,
                                speedKmh = protoEdge.speedKmh
                            ))
                        }

                        val decodeTimeMs = System.currentTimeMillis() - decodeStart
                        val lodTier = response.headers()["X-LOD-Tier"] ?: "?"
                        Log.d(TAG, "📦 BBox proto: ${graph.nodeCount()} nodes, LOD=$lodTier")
                        return@withContext GraphResult(graph, "protobuf-bbox", bytes.size, decodeTimeMs)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ BBox protobuf failed: ${e.message}")
        }

        // Attempt 2: JSON via subgraph-bbox
        try {
            val response = api.getSubgraphByBbox(south, west, north, east)
            if (response.isSuccessful) {
                val body = response.body()
                if (body != null) {
                    val jsonStr = body.string()
                    val decodeStart = System.currentTimeMillis()
                    val json = JSONObject(jsonStr)
                    val graph = Graph()

                    val nodesArray = json.getJSONArray("nodes")
                    for (i in 0 until nodesArray.length()) {
                        val n = nodesArray.getJSONObject(i)
                        graph.addNode(Node(
                            id = n.getString("id"),
                            lat = n.getDouble("lat"),
                            lng = n.getDouble("lng"),
                            name = n.optString("name", "")
                        ))
                    }
                    val edgesArray = json.getJSONArray("edges")
                    for (i in 0 until edgesArray.length()) {
                        val e = edgesArray.getJSONObject(i)
                        graph.addEdge(Edge(
                            from = e.getString("from"),
                            to = e.getString("to"),
                            distance = e.getDouble("distance"),
                            trafficMultiplier = e.optDouble("trafficMultiplier", 1.0),
                            roadQualityMultiplier = e.optDouble("roadQualityMultiplier", 1.0),
                            isOneWay = e.optBoolean("isOneWay", false),
                            roadType = e.optString("roadType", ""),
                            speedKmh = e.optDouble("speedKmh", 25.0)
                        ))
                    }

                    val decodeTimeMs = System.currentTimeMillis() - decodeStart
                    Log.d(TAG, "📦 BBox JSON: ${graph.nodeCount()} nodes")
                    return@withContext GraphResult(graph, "json-bbox", jsonStr.length, decodeTimeMs)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ BBox JSON failed: ${e.message}")
        }

        null // Non-critical — tiles still show
    }

    // ═══════════════════════════════════════════════
    // Health check (local backend)
    // ═══════════════════════════════════════════════

    suspend fun isBackendHealthy(): Boolean = withContext(Dispatchers.IO) {
        try {
            val response = api.healthCheck()
            response.isSuccessful
        } catch (e: Exception) {
            false
        }
    }
}

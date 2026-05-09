// app/src/main/java/com/bgi/pathfinder/network/BackendService.kt
package com.bgi.pathfinder.network

import com.bgi.pathfinder.models.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * BackendService — Connects the Android app to the Node.js backend.
 *
 * Replaces direct Overpass API calls with our own backend's /route-graph
 * endpoint that uses MongoDB spatial queries with padded bounding boxes.
 *
 * Usage:
 *   val graph = BackendService.fetchRouteGraph(startLat, startLng, endLat, endLng)
 *
 * The backend URL is configured via BASE_URL.
 * For local dev: use 10.0.2.2 (Android emulator → host machine)
 * For production: replace with your AWS/Cloud URL
 */
object BackendService {

    // ── Configuration ──
    // 10.0.2.2 = Android emulator → host machine localhost
    // Replace with your production URL when deploying
    private const val BASE_URL = "http://10.0.2.2:3000"

    // Optional: Set this if you enabled API_KEY in backend .env
    private const val API_KEY = "" // Leave empty if auth is disabled

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Fetch a road network subgraph between two points from our backend.
     * Uses the /route-graph endpoint with dynamic BBox + padding.
     *
     * @param startLat Start point latitude
     * @param startLng Start point longitude
     * @param endLat End point latitude
     * @param endLng End point longitude
     * @param paddingKm Buffer around the bounding box in km (default 1.5)
     * @return Graph object populated with nodes and edges
     */
    suspend fun fetchRouteGraph(
        startLat: Double, startLng: Double,
        endLat: Double, endLng: Double,
        paddingKm: Double = 1.5
    ): Graph = withContext(Dispatchers.IO) {
        val url = "$BASE_URL/api/map/route-graph" +
            "?startLat=$startLat&startLng=$startLng" +
            "&endLat=$endLat&endLng=$endLng" +
            "&padding=$paddingKm"

        val requestBuilder = Request.Builder()
            .url(url)
            .header("User-Agent", "BGIPathfinder/1.0")

        // Add API key header if configured
        if (API_KEY.isNotEmpty()) {
            requestBuilder.header("x-api-key", API_KEY)
        }

        val response = client.newCall(requestBuilder.build()).execute()

        if (!response.isSuccessful) {
            val errorBody = response.body?.string() ?: "Unknown error"
            throw Exception("Backend error ${response.code}: $errorBody")
        }

        val body = response.body?.string() ?: throw Exception("Empty response from backend")
        parseBackendResponse(body)
    }

    /**
     * Find the nearest road node to a coordinate via our backend.
     *
     * @return Node or null if nothing found within 1km
     */
    suspend fun findNearestNode(lat: Double, lng: Double): Node? = withContext(Dispatchers.IO) {
        val url = "$BASE_URL/api/map/nearest?lat=$lat&lng=$lng"

        val requestBuilder = Request.Builder()
            .url(url)
            .header("User-Agent", "BGIPathfinder/1.0")

        if (API_KEY.isNotEmpty()) {
            requestBuilder.header("x-api-key", API_KEY)
        }

        val response = client.newCall(requestBuilder.build()).execute()

        if (!response.isSuccessful) return@withContext null

        val body = response.body?.string() ?: return@withContext null
        val json = JSONObject(body)

        Node(
            id = json.getString("id"),
            lat = json.getDouble("lat"),
            lng = json.getDouble("lng"),
            name = json.optString("name", "")
        )
    }

    /**
     * Get database stats (node/edge count) from backend.
     */
    suspend fun getStats(): Pair<Int, Int>? = withContext(Dispatchers.IO) {
        try {
            val url = "$BASE_URL/api/map/stats"
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return@withContext null

            val json = JSONObject(response.body?.string() ?: return@withContext null)
            Pair(json.getInt("nodeCount"), json.getInt("edgeCount"))
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Check if the backend server is reachable and healthy.
     */
    suspend fun isHealthy(): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$BASE_URL/health")
                .build()
            val response = client.newCall(request).execute()
            response.isSuccessful
        } catch (e: Exception) {
            false
        }
    }

    // ── Parse JSON response into Graph ──
    private fun parseBackendResponse(json: String): Graph {
        val graph = Graph()
        val response = JSONObject(json)

        // Parse nodes
        val nodesArray = response.getJSONArray("nodes")
        for (i in 0 until nodesArray.length()) {
            val n = nodesArray.getJSONObject(i)
            graph.addNode(Node(
                id = n.getString("id"),
                lat = n.getDouble("lat"),
                lng = n.getDouble("lng"),
                name = n.optString("name", "")
            ))
        }

        // Parse edges
        val edgesArray = response.getJSONArray("edges")
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

        return graph
    }
}

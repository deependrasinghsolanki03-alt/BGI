// app/src/main/java/com/bgi/pathfinder/network/OverpassService.kt
package com.bgi.pathfinder.network

import com.bgi.pathfinder.models.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Overpass API service — fetches real road network data from OpenStreetMap.
 * Limits query to prevent OOM on large areas.
 */
object OverpassService {

    private const val OVERPASS_URL = "https://overpass-api.de/api/interpreter"
    private const val MAX_BBOX_DEGREES = 0.05 // ~5km max in any direction

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * Fetch road network for the bounding box covering two points.
     * Limits area and road types to prevent OOM.
     */
    suspend fun fetchRoadNetwork(
        lat1: Double, lon1: Double,
        lat2: Double, lon2: Double
    ): Graph = withContext(Dispatchers.IO) {
        // Calculate bounding box with small padding
        val centerLat = (lat1 + lat2) / 2
        val centerLon = (lon1 + lon2) / 2
        val halfLatSpan = minOf(Math.abs(lat1 - lat2) / 2 + 0.005, MAX_BBOX_DEGREES)
        val halfLonSpan = minOf(Math.abs(lon1 - lon2) / 2 + 0.005, MAX_BBOX_DEGREES)

        val south = centerLat - halfLatSpan
        val north = centerLat + halfLatSpan
        val west = centerLon - halfLonSpan
        val east = centerLon + halfLonSpan

        // Use only major roads to keep graph small
        val roadFilter = if (halfLatSpan > 0.03 || halfLonSpan > 0.03) {
            // Large area — major roads only
            "motorway|trunk|primary|secondary"
        } else {
            // Small area — include tertiary + residential
            "motorway|trunk|primary|secondary|tertiary|residential"
        }

        val query = """
            [out:json][timeout:25];
            way["highway"~"^($roadFilter)$"]($south,$west,$north,$east);
            (._;>;);
            out body;
        """.trimIndent()

        val requestBody = "data=${java.net.URLEncoder.encode(query, "UTF-8")}"
            .toRequestBody("application/x-www-form-urlencoded".toMediaType())

        val request = Request.Builder()
            .url(OVERPASS_URL)
            .header("User-Agent", "BGIPathfinder/1.0")
            .post(requestBody)
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            throw Exception("Overpass API error: ${response.code}")
        }

        val body = response.body?.string() ?: throw Exception("Empty response")
        parseOverpassResponse(body)
    }

    /** Parse Overpass JSON into a Graph — with node limit safety */
    private fun parseOverpassResponse(json: String): Graph {
        val graph = Graph()
        val jsonObj = JSONObject(json)
        val elements = jsonObj.getJSONArray("elements")

        // Pass 1: Collect all nodes
        val osmNodes = mutableMapOf<Long, Pair<Double, Double>>()
        for (i in 0 until elements.length()) {
            val el = elements.getJSONObject(i)
            if (el.getString("type") == "node") {
                osmNodes[el.getLong("id")] = Pair(el.getDouble("lat"), el.getDouble("lon"))
            }
        }

        // Safety: if too many nodes, skip residential
        if (osmNodes.size > 50000) {
            throw Exception("Area too large (${osmNodes.size} nodes). Try closer points.")
        }

        // Pass 2: Process ways → edges
        for (i in 0 until elements.length()) {
            val el = elements.getJSONObject(i)
            if (el.getString("type") != "way") continue

            val tags = el.optJSONObject("tags") ?: continue
            val highway = tags.optString("highway", "")
            if (highway.isEmpty()) continue

            val nodeRefs = el.getJSONArray("nodes")
            val isOneWay = isOneWayRoad(tags)
            val weights = getRoadWeights(highway)
            val speed = getRoadSpeed(highway)

            for (j in 0 until nodeRefs.length() - 1) {
                val fromId = nodeRefs.getLong(j)
                val toId = nodeRefs.getLong(j + 1)

                val fromCoord = osmNodes[fromId] ?: continue
                val toCoord = osmNodes[toId] ?: continue

                val fromNodeId = fromId.toString()
                val toNodeId = toId.toString()

                graph.addNode(Node(fromNodeId, fromCoord.first, fromCoord.second))
                graph.addNode(Node(toNodeId, toCoord.first, toCoord.second))

                val dist = Graph.haversineDistance(
                    fromCoord.first, fromCoord.second,
                    toCoord.first, toCoord.second
                )

                graph.addEdge(Edge(
                    from = fromNodeId,
                    to = toNodeId,
                    distance = dist,
                    trafficMultiplier = weights.first,
                    roadQualityMultiplier = weights.second,
                    isOneWay = isOneWay,
                    roadType = highway,
                    speedKmh = speed
                ))
            }
        }

        return graph
    }

    private fun isOneWayRoad(tags: JSONObject): Boolean {
        val oneway = tags.optString("oneway", "no")
        val junction = tags.optString("junction", "")
        return oneway == "yes" || oneway == "1" || junction == "roundabout"
    }

    private fun getRoadWeights(highway: String): Pair<Double, Double> {
        return when (highway) {
            "motorway", "motorway_link" -> Pair(1.0, 0.8)
            "trunk", "trunk_link" -> Pair(1.3, 0.9)
            "primary", "primary_link" -> Pair(1.5, 1.0)
            "secondary", "secondary_link" -> Pair(1.2, 1.1)
            "tertiary", "tertiary_link" -> Pair(1.0, 1.2)
            "residential" -> Pair(0.8, 1.5)
            else -> Pair(1.0, 1.3)
        }
    }

    private fun getRoadSpeed(highway: String): Double {
        return when (highway) {
            "motorway", "motorway_link" -> 80.0
            "trunk", "trunk_link" -> 60.0
            "primary", "primary_link" -> 45.0
            "secondary", "secondary_link" -> 35.0
            "tertiary", "tertiary_link" -> 30.0
            "residential" -> 20.0
            else -> 25.0
        }
    }
}

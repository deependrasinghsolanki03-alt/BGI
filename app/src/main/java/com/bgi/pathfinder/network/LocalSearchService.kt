// app/src/main/java/com/bgi/pathfinder/network/LocalSearchService.kt
package com.bgi.pathfinder.network

import android.util.Log
import com.bgi.pathfinder.models.SearchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

/**
 * Local Search Service — replaces Nominatim with local MongoDB queries.
 *
 * 100% OFFLINE — queries our Node.js backend which searches
 * the OsmNode/OsmWay collections in local MongoDB.
 *
 * Works on hotspot (phone ↔ laptop) without internet.
 *
 * Features:
 *   - Proximity sorting: pass lat/lng → results sorted by distance
 *   - distanceKm in response → shown in search dropdown
 *   - Prefix + contains search → fast index scan + fallback
 *
 * Endpoint: GET /api/map/search?q=<query>&limit=10&lat=28.6&lng=77.2
 */
object LocalSearchService {

    private val api = RetrofitClient.api
    private const val TAG = "LocalSearch"

    /**
     * Search for places in local MongoDB.
     *
     * @param query Search string (min 2 chars)
     * @param lat User's current latitude (null if GPS unavailable)
     * @param lng User's current longitude (null if GPS unavailable)
     * @return List of SearchResult sorted by proximity (if coords provided)
     */
    suspend fun search(
        query: String,
        lat: Double? = null,
        lng: Double? = null
    ): List<SearchResult> = withContext(Dispatchers.IO) {
        if (query.length < 2) return@withContext emptyList()

        try {
            val response = api.searchPlaces(
                query = query,
                limit = 15,
                lat = lat,
                lng = lng
            )

            if (!response.isSuccessful) {
                Log.w(TAG, "Search API error: ${response.code()}")
                return@withContext emptyList()
            }

            val body = response.body()?.string() ?: return@withContext emptyList()
            val jsonArray = JSONArray(body)
            val results = mutableListOf<SearchResult>()

            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)

                // Parse distance (null if no user coords provided)
                val distanceKm = if (obj.has("distanceKm") && !obj.isNull("distanceKm")) {
                    obj.getDouble("distanceKm")
                } else null

                // Build display name with distance
                val rawName = obj.getString("displayName")
                val displayName = if (distanceKm != null) {
                    if (distanceKm < 1.0) {
                        "$rawName • ${(distanceKm * 1000).toInt()}m"
                    } else {
                        "$rawName • ${String.format("%.1f", distanceKm)}km"
                    }
                } else {
                    rawName
                }

                results.add(
                    SearchResult(
                        displayName = displayName,
                        lat = obj.getDouble("lat"),
                        lon = obj.getDouble("lon"),
                        type = obj.optString("type", "")
                    )
                )
            }

            Log.d(TAG, "🔍 '$query' → ${results.size} results" +
                    if (lat != null) " (near ${String.format("%.2f", lat)},${String.format("%.2f", lng)})" else "")
            results
        } catch (e: Exception) {
            Log.e(TAG, "Local search failed: ${e.message}")
            emptyList()
        }
    }
}

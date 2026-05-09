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
 * Endpoint: GET /api/map/search?q=<query>&limit=10
 */
object LocalSearchService {

    private val api = RetrofitClient.api
    private const val TAG = "LocalSearchService"

    /**
     * Search for places in local MongoDB.
     * @param query Search string (min 2 chars)
     * @return List of SearchResult matching the query
     */
    suspend fun search(query: String): List<SearchResult> = withContext(Dispatchers.IO) {
        if (query.length < 2) return@withContext emptyList()

        try {
            val response = api.searchPlaces(query, 10)

            if (!response.isSuccessful) {
                Log.w(TAG, "Search API error: ${response.code()}")
                return@withContext emptyList()
            }

            val body = response.body()?.string() ?: return@withContext emptyList()
            val jsonArray = JSONArray(body)
            val results = mutableListOf<SearchResult>()

            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                results.add(
                    SearchResult(
                        displayName = obj.getString("displayName"),
                        lat = obj.getDouble("lat"),
                        lon = obj.getDouble("lon"),
                        type = obj.optString("type", "")
                    )
                )
            }

            Log.d(TAG, "Local search '$query' → ${results.size} results")
            results
        } catch (e: Exception) {
            Log.e(TAG, "Local search failed: ${e.message}")
            emptyList()
        }
    }
}

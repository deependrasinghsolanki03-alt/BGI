// app/src/main/java/com/bgi/pathfinder/network/NominatimService.kt
package com.bgi.pathfinder.network

import com.bgi.pathfinder.models.SearchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.util.concurrent.TimeUnit

/**
 * Nominatim Geocoding API service for OpenStreetMap.
 * Converts place names → coordinates.
 * Usage policy: max 1 req/sec, requires User-Agent.
 */
object NominatimService {

    private const val BASE_URL = "https://nominatim.openstreetmap.org/search"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    /**
     * Search for places matching [query].
     * @return List of SearchResult (max 5)
     */
    suspend fun search(query: String): List<SearchResult> = withContext(Dispatchers.IO) {
        if (query.length < 3) return@withContext emptyList()

        try {
            val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
            val url = "$BASE_URL?q=$encodedQuery&format=json&limit=5&addressdetails=0"

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "BGIPathfinder/1.0 (Android; contact@bgi.com)")
                .get()
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return@withContext emptyList()

            val jsonArray = JSONArray(body)
            val results = mutableListOf<SearchResult>()

            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                results.add(
                    SearchResult(
                        displayName = obj.getString("display_name"),
                        lat = obj.getDouble("lat"),
                        lon = obj.getDouble("lon"),
                        type = obj.optString("type", "")
                    )
                )
            }

            results
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }
}

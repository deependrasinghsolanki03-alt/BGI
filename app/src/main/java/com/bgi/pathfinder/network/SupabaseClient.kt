// app/src/main/java/com/bgi/pathfinder/network/SupabaseClient.kt
package com.bgi.pathfinder.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Lightweight Supabase REST client — no heavy SDK needed.
 * Uses OkHttp to POST location data to the 'locations' table.
 *
 * Table schema:
 *   locations (id: auto, lat: float8, lng: float8, sos_id: text, created_at: timestamptz)
 */
object SupabaseClient {

    private const val TAG = "Supabase"

    // ⚠️ REPLACE with your Supabase project values
    private const val SUPABASE_URL = "https://YOUR_PROJECT.supabase.co"
    private const val SUPABASE_ANON_KEY = "YOUR_ANON_KEY"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    /**
     * Insert a location row into the 'locations' table.
     * POST /rest/v1/locations
     */
    suspend fun insertLocation(lat: Double, lng: Double, sosId: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val json = JSONObject().apply {
                    put("lat", lat)
                    put("lng", lng)
                    put("sos_id", sosId)
                }

                val body = json.toString()
                    .toRequestBody("application/json".toMediaType())

                val request = Request.Builder()
                    .url("$SUPABASE_URL/rest/v1/locations")
                    .post(body)
                    .header("apikey", SUPABASE_ANON_KEY)
                    .header("Authorization", "Bearer $SUPABASE_ANON_KEY")
                    .header("Content-Type", "application/json")
                    .header("Prefer", "return=minimal")
                    .build()

                val response = client.newCall(request).execute()
                val success = response.isSuccessful
                if (!success) {
                    Log.e(TAG, "Insert failed: ${response.code} ${response.body?.string()}")
                }
                response.close()
                success
            } catch (e: Exception) {
                Log.e(TAG, "Insert error: ${e.message}")
                false
            }
        }
    }
}

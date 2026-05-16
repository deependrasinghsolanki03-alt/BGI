// app/src/main/java/com/bgi/pathfinder/network/SupabaseClient.kt
package com.bgi.pathfinder.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Lightweight Supabase REST client.
 * Handles: locations table, sos_chats table, sos-recordings storage bucket.
 */
object SupabaseClient {

    private const val TAG = "Supabase"
    const val SUPABASE_URL = "https://gyjdbqmdoofkywmnxhyc.supabase.co"
    const val SUPABASE_ANON_KEY = "sb_publishable_qRe0N5vaN1_Ziqte5oLBEw_QT4NZHLA"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    // ═══════════════════════════════════════
    // Locations Table
    // ═══════════════════════════════════════

    suspend fun insertLocation(lat: Double, lng: Double, sosId: String): Boolean {
        return postRow("locations", JSONObject().apply {
            put("lat", lat); put("lng", lng); put("sos_id", sosId)
        })
    }

    // ═══════════════════════════════════════
    // SOS Chats Table
    // ═══════════════════════════════════════

    suspend fun sendChatMessage(sosId: String, senderType: String, msgType: String, content: String): Boolean {
        return postRow("sos_chats", JSONObject().apply {
            put("sos_id", sosId)
            put("sender_type", senderType)
            put("message_type", msgType)
            put("content", content)
        })
    }

    /** Poll for new chat messages since a given ID. */
    suspend fun getChatMessages(sosId: String, afterId: Long = 0): List<JSONObject> {
        return withContext(Dispatchers.IO) {
            try {
                val url = "$SUPABASE_URL/rest/v1/sos_chats?sos_id=eq.$sosId&id=gt.$afterId&order=id.asc"
                val request = Request.Builder().url(url).get()
                    .header("apikey", SUPABASE_ANON_KEY)
                    .header("Authorization", "Bearer $SUPABASE_ANON_KEY")
                    .build()
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) { response.close(); return@withContext emptyList() }
                val arr = JSONArray(response.body!!.string())
                response.close()
                (0 until arr.length()).map { arr.getJSONObject(it) }
            } catch (e: Exception) {
                Log.e(TAG, "getChatMessages error: ${e.message}")
                emptyList()
            }
        }
    }

    // ═══════════════════════════════════════
    // Storage — Audio Upload
    // ═══════════════════════════════════════

    /** Upload audio file to sos-recordings bucket, return public URL. */
    suspend fun uploadAudio(file: File, sosId: String): String? {
        return withContext(Dispatchers.IO) {
            try {
                val fileName = "${sosId}_${System.currentTimeMillis()}.mp3"
                val fileBytes = file.readBytes()
                val body = fileBytes.toRequestBody("audio/mpeg".toMediaType())

                val request = Request.Builder()
                    .url("$SUPABASE_URL/storage/v1/object/sos-recordings/$fileName")
                    .post(body)
                    .header("apikey", SUPABASE_ANON_KEY)
                    .header("Authorization", "Bearer $SUPABASE_ANON_KEY")
                    .header("Content-Type", "audio/mpeg")
                    .build()

                val response = client.newCall(request).execute()
                response.close()

                if (response.isSuccessful) {
                    val publicUrl = "$SUPABASE_URL/storage/v1/object/public/sos-recordings/$fileName"
                    Log.d(TAG, "Audio uploaded: $publicUrl")
                    publicUrl
                } else {
                    Log.e(TAG, "Upload failed: ${response.code}")
                    null
                }
            } catch (e: Exception) {
                Log.e(TAG, "Upload error: ${e.message}")
                null
            }
        }
    }

    // ═══════════════════════════════════════
    // Generic Helper
    // ═══════════════════════════════════════

    private suspend fun postRow(table: String, json: JSONObject): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val body = json.toString().toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url("$SUPABASE_URL/rest/v1/$table")
                    .post(body)
                    .header("apikey", SUPABASE_ANON_KEY)
                    .header("Authorization", "Bearer $SUPABASE_ANON_KEY")
                    .header("Content-Type", "application/json")
                    .header("Prefer", "return=minimal")
                    .build()
                val response = client.newCall(request).execute()
                val ok = response.isSuccessful
                if (!ok) Log.e(TAG, "$table insert failed: ${response.code}")
                response.close()
                ok
            } catch (e: Exception) {
                Log.e(TAG, "$table error: ${e.message}")
                false
            }
        }
    }
}

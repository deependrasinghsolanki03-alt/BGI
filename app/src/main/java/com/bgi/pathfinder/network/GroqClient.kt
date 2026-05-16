// app/src/main/java/com/bgi/pathfinder/network/GroqClient.kt
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
import java.util.concurrent.TimeUnit

/**
 * Groq Cloud API client for AI-powered destination extraction.
 *
 * Takes natural language input like "Mujhe Indore jana hai"
 * and returns structured JSON with destination coordinates.
 *
 * Uses llama-3.3-70b-versatile model for fast, accurate responses.
 */
object GroqClient {

    private const val TAG = "Groq"
    // ⚠️ REPLACE with your Groq API key from: https://console.groq.com/keys
    private const val GROQ_API_KEY = "YOUR_GROQ_API_KEY"
    private const val BASE_URL = "https://api.groq.com/openai/v1/chat/completions"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * System prompt that forces Groq to return ONLY structured JSON.
     */
    private const val SYSTEM_PROMPT = """You are a navigation assistant for a women safety app in India.
The user will tell you a destination in natural language (Hindi, English, or Hinglish).

Your ONLY job is to extract the destination name and return its coordinates.

RULES:
1. Return ONLY valid JSON, no extra text.
2. Format: {"destination": "Place Name", "lat": 00.0000, "lng": 00.0000}
3. If the user mentions a well-known city, landmark, or place in India, return its real coordinates.
4. If you cannot identify the place, return: {"error": "Location not found"}
5. Never add explanations, greetings, or markdown. ONLY JSON."""

    /**
     * Send user message to Groq and get destination JSON.
     * Returns the raw JSON string from the AI response.
     */
    suspend fun extractDestination(userMessage: String): String? {
        return withContext(Dispatchers.IO) {
            try {
                val requestJson = JSONObject().apply {
                    put("model", "llama-3.3-70b-versatile")
                    put("temperature", 0.1)
                    put("max_tokens", 150)
                    put("messages", JSONArray().apply {
                        put(JSONObject().apply {
                            put("role", "system")
                            put("content", SYSTEM_PROMPT)
                        })
                        put(JSONObject().apply {
                            put("role", "user")
                            put("content", userMessage)
                        })
                    })
                }

                val body = requestJson.toString()
                    .toRequestBody("application/json".toMediaType())

                val request = Request.Builder()
                    .url(BASE_URL)
                    .post(body)
                    .header("Authorization", "Bearer $GROQ_API_KEY")
                    .header("Content-Type", "application/json")
                    .build()

                val response = client.newCall(request).execute()
                val responseBody = response.body?.string()

                if (!response.isSuccessful) {
                    Log.e(TAG, "Groq error ${response.code}: $responseBody")
                    return@withContext null
                }

                // Extract the assistant's message content
                val json = JSONObject(responseBody!!)
                val content = json.getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")
                    .trim()

                Log.d(TAG, "Groq response: $content")
                content
            } catch (e: Exception) {
                Log.e(TAG, "Groq call failed: ${e.message}")
                null
            }
        }
    }
}

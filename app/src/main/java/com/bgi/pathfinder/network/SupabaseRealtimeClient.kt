// app/src/main/java/com/bgi/pathfinder/network/SupabaseRealtimeClient.kt
package com.bgi.pathfinder.network

import android.util.Log
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Supabase Realtime Broadcast Client
 *
 * Uses OkHttp WebSocket (already in project) to connect to
 * Supabase Realtime and broadcast location updates via Phoenix channels.
 *
 * Protocol: Phoenix Channels over WebSocket
 *   1. Connect to wss://PROJECT.supabase.co/realtime/v1/websocket
 *   2. Join channel "realtime:sos-{UUID}"
 *   3. Broadcast location payloads on that channel
 *   4. Send heartbeat every 30s to keep connection alive
 *
 * No extra SDK needed — uses OkHttp which is already a dependency.
 */
class SupabaseRealtimeClient(
    private val supabaseUrl: String,   // e.g. "https://xxxx.supabase.co"
    private val supabaseAnonKey: String
) {
    companion object {
        private const val TAG = "SupabaseRT"
    }

    private var webSocket: WebSocket? = null
    private val refCounter = AtomicInteger(0)
    private var currentChannelTopic: String? = null
    private var isJoined = false
    private var heartbeatRunnable: Runnable? = null
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * Connect to Supabase Realtime WebSocket and join a broadcast channel.
     * @param channelName The unique SOS session ID (e.g. "sos-abc123")
     */
    fun connect(channelName: String) {
        val topic = "realtime:$channelName"
        currentChannelTopic = topic

        // Build WebSocket URL
        val wsUrl = supabaseUrl
            .replace("https://", "wss://")
            .replace("http://", "ws://") +
            "/realtime/v1/websocket?apikey=$supabaseAnonKey&vsn=1.0.0"

        Log.d(TAG, "Connecting to: $wsUrl")

        val client = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)  // No read timeout for WebSocket
            .pingInterval(25, TimeUnit.SECONDS)
            .build()

        val request = Request.Builder()
            .url(wsUrl)
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(ws: WebSocket, response: Response) {
                Log.d(TAG, "✅ WebSocket connected")
                joinChannel(ws, topic)
                startHeartbeat(ws)
            }

            override fun onMessage(ws: WebSocket, text: String) {
                Log.d(TAG, "← $text")
                try {
                    val json = JSONObject(text)
                    val event = json.optString("event")
                    if (event == "phx_reply") {
                        val payload = json.optJSONObject("payload")
                        val status = payload?.optString("status")
                        if (status == "ok" && json.optString("ref") == "join") {
                            isJoined = true
                            Log.d(TAG, "✅ Joined channel: $topic")
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse message: ${e.message}")
                }
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "❌ WebSocket error: ${t.message}")
                isJoined = false
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed: $reason")
                isJoined = false
            }
        })
    }

    /**
     * Join a Supabase Realtime channel with broadcast config.
     */
    private fun joinChannel(ws: WebSocket, topic: String) {
        val joinMsg = JSONObject().apply {
            put("topic", topic)
            put("event", "phx_join")
            put("payload", JSONObject().apply {
                put("config", JSONObject().apply {
                    put("broadcast", JSONObject().apply {
                        put("self", true)
                        put("ack", false)
                    })
                })
            })
            put("ref", "join")
        }
        ws.send(joinMsg.toString())
        Log.d(TAG, "→ Joining channel: $topic")
    }

    /**
     * Broadcast a location update to the channel.
     */
    fun broadcastLocation(lat: Double, lng: Double, timestamp: Long) {
        if (!isJoined || webSocket == null || currentChannelTopic == null) {
            Log.w(TAG, "Cannot broadcast — not connected/joined")
            return
        }

        val msg = JSONObject().apply {
            put("topic", currentChannelTopic)
            put("event", "broadcast")
            put("payload", JSONObject().apply {
                put("type", "broadcast")
                put("event", "location_update")
                put("payload", JSONObject().apply {
                    put("lat", lat)
                    put("lng", lng)
                    put("timestamp", timestamp)
                })
            })
            put("ref", refCounter.incrementAndGet().toString())
        }

        webSocket?.send(msg.toString())
        Log.d(TAG, "📡 Broadcast: $lat, $lng")
    }

    /**
     * Send Phoenix heartbeat every 30 seconds to keep connection alive.
     */
    private fun startHeartbeat(ws: WebSocket) {
        heartbeatRunnable = object : Runnable {
            override fun run() {
                val hb = JSONObject().apply {
                    put("topic", "phoenix")
                    put("event", "heartbeat")
                    put("payload", JSONObject())
                    put("ref", refCounter.incrementAndGet().toString())
                }
                ws.send(hb.toString())
                handler.postDelayed(this, 30_000)
            }
        }
        handler.postDelayed(heartbeatRunnable!!, 30_000)
    }

    /**
     * Disconnect WebSocket and stop heartbeat.
     */
    fun disconnect() {
        heartbeatRunnable?.let { handler.removeCallbacks(it) }
        webSocket?.close(1000, "SOS tracking stopped")
        webSocket = null
        isJoined = false
        currentChannelTopic = null
        Log.d(TAG, "🔌 Disconnected")
    }

    fun isConnected(): Boolean = isJoined
}

// app/src/main/java/com/bgi/pathfinder/network/RetrofitClient.kt
package com.bgi.pathfinder.network

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit

/**
 * Retrofit client configured for Protobuf binary responses.
 *
 * Key configuration:
 *   - No converter factory (we read raw ResponseBody bytes)
 *   - OkHttp handles gzip decompression automatically
 *   - API key interceptor for authenticated requests
 *   - Generous timeouts for large spatial queries
 *
 * Usage:
 *   val api = RetrofitClient.api
 *   val response = api.streamMapGraph(28.61, 77.20, 28.63, 77.25)
 */
object RetrofitClient {

    // ═══════════════════════════════════════════════
    // Configuration — 100% LOCAL (no internet needed)
    // ═══════════════════════════════════════════════

    // ⚠️ CHANGE THIS to your laptop's IP when using hotspot!
    // Find your IP: Windows → ipconfig → Wi-Fi → IPv4 Address
    //
    // Emulator    → "http://10.0.2.2:3000/"
    // Hotspot     → "http://<YOUR_LAPTOP_IP>:3000/"
    // Production  → "https://your-server.com/"
    private const val BASE_URL = "http://10.190.38.31:3000/"

    // Set this if you enabled API_KEY in backend .env
    // Leave empty for open access
    private const val API_KEY = ""

    // ═══════════════════════════════════════════════
    // OkHttp Client
    // ═══════════════════════════════════════════════

    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            // Timeouts for large spatial queries
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)

            // API key interceptor
            .addInterceptor(apiKeyInterceptor())

            // User-Agent header
            .addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header("User-Agent", "BGIPathfinder/1.0 (Android)")
                        .build()
                )
            }

            // OkHttp automatically handles gzip decompression
            // when the server sends Content-Encoding: gzip
            .build()
    }

    /**
     * Adds x-api-key header if API_KEY is configured.
     */
    private fun apiKeyInterceptor(): Interceptor {
        return Interceptor { chain ->
            val requestBuilder = chain.request().newBuilder()
            if (API_KEY.isNotEmpty()) {
                requestBuilder.header("x-api-key", API_KEY)
            }
            chain.proceed(requestBuilder.build())
        }
    }

    // ═══════════════════════════════════════════════
    // Retrofit Instance
    // ═══════════════════════════════════════════════

    /**
     * Retrofit configured for RAW binary responses.
     *
     * Why no converter factory?
     *   - Protobuf responses are raw bytes, not JSON
     *   - We use ResponseBody.bytes() to get the binary data
     *   - Then decode manually with protobuf-javalite
     *   - This gives us full control over the deserialization
     */
    private val retrofit: Retrofit by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            // No converter factory needed — we read raw bytes
            // OkHttp auto-decompresses gzip for us
            .build()
    }

    /**
     * The API service instance — use this everywhere.
     *
     * Example:
     *   val response = RetrofitClient.api.streamMapGraph(28.61, 77.20, 28.63, 77.25)
     */
    val api: MapApiService by lazy {
        retrofit.create(MapApiService::class.java)
    }
}

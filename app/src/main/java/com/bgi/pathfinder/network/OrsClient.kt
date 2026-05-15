// app/src/main/java/com/bgi/pathfinder/network/OrsClient.kt
package com.bgi.pathfinder.network

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit

/**
 * Retrofit client for OpenRouteService cloud API.
 *
 * All search and routing goes through ORS — no local backend needed.
 * The API key is added automatically to every request via an interceptor.
 *
 * Get your free key: https://openrouteservice.org/dev/#/signup
 */
object OrsClient {

    // ⚠️ REPLACE with your own ORS API key!
    private const val ORS_API_KEY = "eyJvcmciOiI1YjNjZTM1OTc4NTExMTAwMDFjZjYyNDgiLCJpZCI6IjU2YjkyN2Q2MDNjZDQ2MTU4MGVkNmU3M2FmYjI5OWU3IiwiaCI6Im11cm11cjY0In0="
    private const val BASE_URL = "https://api.openrouteservice.org/"

    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            // Add API key to every request
            .addInterceptor(Interceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("Authorization", ORS_API_KEY)
                    .header("Accept", "application/json, application/geo+json")
                    .header("User-Agent", "BGIPathfinder/2.0 (Android)")
                    .build()
                chain.proceed(request)
            })
            .build()
    }

    private val retrofit: Retrofit by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .build()
    }

    val api: OrsApiService by lazy {
        retrofit.create(OrsApiService::class.java)
    }
}

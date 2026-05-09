// app/src/main/java/com/bgi/pathfinder/network/MapApiService.kt
package com.bgi.pathfinder.network

import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * Retrofit interface for the Pathfinder backend API.
 *
 * 100% LOCAL — all endpoints hit our Node.js + MongoDB backend.
 * No Nominatim, no Overpass, no external APIs.
 * Works fully offline on hotspot between phone ↔ laptop.
 */
interface MapApiService {

    // ═══════════════════════════════════════════════
    // SEARCH — Local geocoding (replaces Nominatim)
    // ═══════════════════════════════════════════════

    /**
     * Search places in local MongoDB (OsmNode + OsmWay).
     * GET /api/map/search?q=Red+Fort&limit=5
     */
    @GET("api/map/search")
    suspend fun searchPlaces(
        @Query("q") query: String,
        @Query("limit") limit: Int = 10
    ): Response<ResponseBody>

    /**
     * Reverse geocode — find nearest named place to a coordinate.
     * GET /api/map/reverse?lat=28.6&lng=77.2
     */
    @GET("api/map/reverse")
    suspend fun reverseGeocode(
        @Query("lat") lat: Double,
        @Query("lng") lng: Double
    ): Response<ResponseBody>

    // ═══════════════════════════════════════════════
    // PROTOBUF STREAMING — Primary route data
    // ═══════════════════════════════════════════════

    /**
     * Fetch road network as Protobuf binary between two points.
     * Data comes from local MapNode/MapEdge collections.
     */
    @GET("api/v1/stream-map")
    suspend fun streamMapGraph(
        @Query("startLat") startLat: Double,
        @Query("startLng") startLng: Double,
        @Query("endLat") endLat: Double,
        @Query("endLng") endLng: Double,
        @Query("padding") padding: Double = 1.5
    ): Response<ResponseBody>

    // ═══════════════════════════════════════════════
    // JSON API — Fallback for route data
    // ═══════════════════════════════════════════════

    /**
     * Fetch road network as JSON between two points.
     * Same local MongoDB data, just JSON format.
     */
    @GET("api/map/route-graph")
    suspend fun getRouteGraphJson(
        @Query("startLat") startLat: Double,
        @Query("startLng") startLng: Double,
        @Query("endLat") endLat: Double,
        @Query("endLng") endLng: Double,
        @Query("padding") padding: Double = 1.5
    ): Response<ResponseBody>

    // ═══════════════════════════════════════════════
    // UTILITY
    // ═══════════════════════════════════════════════

    @GET("api/v1/stream-map/info")
    suspend fun getStreamInfo(): Response<ResponseBody>

    @GET("health")
    suspend fun healthCheck(): Response<ResponseBody>
}


// app/src/main/java/com/bgi/pathfinder/network/OrsApiService.kt
package com.bgi.pathfinder.network

import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.*

/**
 * OpenRouteService (ORS) Cloud API — replaces local MongoDB backend.
 *
 * Two endpoints used:
 *   1. Geocoding (search) → /geocode/search
 *   2. Directions (routing) → /v2/directions/driving-car/geojson
 *
 * Free tier: 1000 requests/day — more than enough for a safety app.
 * Docs: https://openrouteservice.org/dev/#/api-docs
 */
interface OrsApiService {

    /**
     * Geocode a search query into lat/lng results.
     * GET https://api.openrouteservice.org/geocode/search?text=Gole+ka+Mandir&size=8
     *
     * Returns GeoJSON FeatureCollection with coordinates + labels.
     */
    @GET("geocode/search")
    suspend fun geocodeSearch(
        @Query("text") query: String,
        @Query("size") limit: Int = 8,
        @Query("boundary.circle.lat") focusLat: Double? = null,
        @Query("boundary.circle.lon") focusLng: Double? = null,
        @Query("boundary.circle.radius") radiusKm: Int? = null
    ): Response<ResponseBody>

    /**
     * Get driving directions between two points.
     * POST https://api.openrouteservice.org/v2/directions/driving-car/geojson
     *
     * Body: { "coordinates": [[lng1,lat1],[lng2,lat2]], "alternative_routes": {"target_count": 3} }
     * Returns GeoJSON with route geometry + distance/duration.
     */
    @POST("v2/directions/{profile}/geojson")
    suspend fun getDirections(
        @Path("profile") profile: String = "driving-car",
        @Body body: RequestBody
    ): Response<ResponseBody>
}

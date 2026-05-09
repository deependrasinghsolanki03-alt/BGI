// app/src/main/java/com/bgi/pathfinder/utils/PolylineSimplifier.kt
package com.bgi.pathfinder.utils

import org.osmdroid.util.GeoPoint
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Douglas-Peucker Polyline Simplification.
 *
 * Reduces the number of points drawn on the map while
 * preserving the visual shape. Critical for performance
 * with 27M+ nodes — phone GPU can't render millions of segments.
 *
 * Usage:
 *   val simplified = PolylineSimplifier.simplify(geoPoints, tolerance = 0.00005)
 *   // tolerance in degrees — ~5 meters at equator
 *
 * How it works:
 *   1. Takes the first and last point as a baseline
 *   2. Finds the point farthest from the baseline
 *   3. If distance > tolerance, recursively split and keep the point
 *   4. If distance < tolerance, discard all intermediate points
 *
 * Result: 5000 points → ~200 points (with identical visual appearance)
 */
object PolylineSimplifier {

    /**
     * Simplify a list of GeoPoints using Douglas-Peucker algorithm.
     *
     * @param points Original polyline points
     * @param tolerance Distance threshold in degrees
     *                  - 0.00001 = ~1m (very detailed)
     *                  - 0.00005 = ~5m (good balance for roads)
     *                  - 0.0001  = ~10m (aggressive, for zoomed out)
     *                  - 0.0005  = ~50m (very aggressive)
     * @return Simplified list of GeoPoints
     */
    fun simplify(points: List<GeoPoint>, tolerance: Double = 0.00005): List<GeoPoint> {
        if (points.size <= 2) return points

        val keep = BooleanArray(points.size) { false }
        keep[0] = true
        keep[points.size - 1] = true

        douglasPeucker(points, 0, points.size - 1, tolerance, keep)

        return points.filterIndexed { index, _ -> keep[index] }
    }

    /**
     * Adaptive simplification based on zoom level.
     * Higher zoom (closer) = more detail, lower zoom = more aggressive.
     *
     * @param points Original polyline points
     * @param zoomLevel Current map zoom level (0-22)
     * @return Simplified list
     */
    fun simplifyForZoom(points: List<GeoPoint>, zoomLevel: Double): List<GeoPoint> {
        val tolerance = when {
            zoomLevel >= 18 -> 0.000005  // Street level — max detail
            zoomLevel >= 16 -> 0.00002   // Neighborhood — high detail
            zoomLevel >= 14 -> 0.00005   // City — balanced
            zoomLevel >= 12 -> 0.0002    // District — moderate
            zoomLevel >= 10 -> 0.001     // Region — aggressive
            else -> 0.005                // Country — very aggressive
        }
        return simplify(points, tolerance)
    }

    /**
     * Recursive Douglas-Peucker implementation.
     */
    private fun douglasPeucker(
        points: List<GeoPoint>,
        startIdx: Int,
        endIdx: Int,
        tolerance: Double,
        keep: BooleanArray
    ) {
        if (endIdx - startIdx < 2) return

        var maxDist = 0.0
        var maxIdx = startIdx

        val start = points[startIdx]
        val end = points[endIdx]

        for (i in (startIdx + 1) until endIdx) {
            val dist = perpendicularDistance(points[i], start, end)
            if (dist > maxDist) {
                maxDist = dist
                maxIdx = i
            }
        }

        if (maxDist > tolerance) {
            keep[maxIdx] = true
            // Recurse on both halves
            douglasPeucker(points, startIdx, maxIdx, tolerance, keep)
            douglasPeucker(points, maxIdx, endIdx, tolerance, keep)
        }
    }

    /**
     * Calculate perpendicular distance from a point to a line segment.
     * Uses simplified calculation in degree space (fast, good enough for display).
     */
    private fun perpendicularDistance(point: GeoPoint, lineStart: GeoPoint, lineEnd: GeoPoint): Double {
        val dx = lineEnd.longitude - lineStart.longitude
        val dy = lineEnd.latitude - lineStart.latitude

        if (dx == 0.0 && dy == 0.0) {
            // Start and end are the same point
            val pdx = point.longitude - lineStart.longitude
            val pdy = point.latitude - lineStart.latitude
            return sqrt(pdx * pdx + pdy * pdy)
        }

        // Perpendicular distance using cross product
        val numerator = abs(
            dy * point.longitude - dx * point.latitude +
                lineEnd.longitude * lineStart.latitude -
                lineEnd.latitude * lineStart.longitude
        )
        val denominator = sqrt(dx * dx + dy * dy)

        return numerator / denominator
    }
}

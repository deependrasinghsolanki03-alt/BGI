// app/src/main/java/com/bgi/pathfinder/map/HybridMapOverlay.kt
package com.bgi.pathfinder.map

import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.util.Log
import com.bgi.pathfinder.models.Edge
import com.bgi.pathfinder.models.Graph
import com.bgi.pathfinder.models.Node
import com.bgi.pathfinder.network.MapRepository
import com.bgi.pathfinder.proto.MapProto
import com.bgi.pathfinder.utils.PolylineSimplifier
import kotlinx.coroutines.*
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.FolderOverlay
import org.osmdroid.views.overlay.Polyline
import java.util.concurrent.atomic.AtomicBoolean

/**
 * HybridMapOverlay — Local Vector Data Layer
 *
 * Sits ON TOP of the standard raster tile layer (MAPNIK).
 * Renders road data fetched from the local Protobuf backend
 * in bold, distinctive colors so they stand out against
 * the background tiles.
 *
 * LOD Logic:
 *   Zoom >= 15 (Close-up) → Request ALL roads (HIGH detail)
 *   Zoom <  15 (Wide-view) → Request only highways (backend LOD filter)
 *
 * The background tiles handle the visual "texture" of the city,
 * while this overlay shows the local road network skeleton
 * with higher Z-index and distinct styling.
 *
 * Architecture:
 *   MapView
 *     └── Layer 0: MAPNIK Tiles (online, cached)
 *     └── Layer 1: GPS Blue Dot
 *     └── Layer 2: HybridMapOverlay (this — FolderOverlay)
 *         └── Polyline per road segment
 *     └── Layer 3+: Route polylines, markers (user actions)
 */
class HybridMapOverlay(
    private val mapView: MapView,
    private val repository: MapRepository
) {

    companion object {
        private const val TAG = "HybridMap"

        // ═══════════════════════════════════════
        // Road Styling — distinct from tile layer
        // ═══════════════════════════════════════

        // Motorway / Trunk — BOLD electric blue with glow
        private const val COLOR_MOTORWAY = 0xFF1565C0.toInt()       // Deep Blue
        private const val COLOR_MOTORWAY_BORDER = 0xFF0D47A1.toInt() // Darker border
        private const val WIDTH_MOTORWAY = 7f
        private const val WIDTH_MOTORWAY_BORDER = 10f

        // Primary — Bright Orange
        private const val COLOR_PRIMARY = 0xFFFF6F00.toInt()        // Amber/Orange
        private const val WIDTH_PRIMARY = 5.5f

        // Secondary — Teal
        private const val COLOR_SECONDARY = 0xFF00897B.toInt()      // Teal
        private const val WIDTH_SECONDARY = 4f

        // Tertiary — Muted violet
        private const val COLOR_TERTIARY = 0xFF7E57C2.toInt()       // Purple
        private const val WIDTH_TERTIARY = 3f

        // Residential / Other — Subtle grey-blue (only at high zoom)
        private const val COLOR_RESIDENTIAL = 0xFF546E7A.toInt()    // Blue Grey
        private const val WIDTH_RESIDENTIAL = 2f

        // Alpha for all vector lines (slightly transparent to see tiles underneath)
        private const val VECTOR_ALPHA = 210

        // Debounce — wait this long after map movement stops before fetching
        private const val FETCH_DEBOUNCE_MS = 600L

        // Max edges to render before simplifying
        private const val MAX_RENDER_EDGES = 5000
    }

    // ═══════════════════════════════════════
    // State
    // ═══════════════════════════════════════

    /** FolderOverlay that holds all vector polylines */
    val folderOverlay = FolderOverlay()

    /** Scope for coroutine fetching */
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    /** Current fetch job (cancelled on new request) */
    private var fetchJob: Job? = null

    /** Currently rendered bbox (avoid re-fetching same area) */
    private var lastRenderedBBox: BoundingBox? = null
    private var lastRenderedZoom: Int = -1

    /** Prevent concurrent fetches */
    private val isFetching = AtomicBoolean(false)

    /** Whether overlay is enabled */
    var isEnabled: Boolean = true
        set(value) {
            field = value
            folderOverlay.isEnabled = value
            if (!value) clearOverlay()
        }

    // ═══════════════════════════════════════
    // Public API
    // ═══════════════════════════════════════

    /**
     * Call this from MapActivity.onCreate() to attach the overlay.
     * Adds the FolderOverlay at a high Z-index above tiles.
     */
    fun attach() {
        folderOverlay.name = "LocalVectorOverlay"
        // Insert at index 1 (above GPS dot at 0, below route polylines)
        val insertIdx = minOf(1, mapView.overlays.size)
        mapView.overlays.add(insertIdx, folderOverlay)
        Log.d(TAG, "✅ Vector overlay attached at index $insertIdx")
    }

    /**
     * Called when map is scrolled/zoomed. Debounces and fetches new data.
     */
    fun onMapMoved() {
        if (!isEnabled) return

        fetchJob?.cancel()
        fetchJob = scope.launch {
            delay(FETCH_DEBOUNCE_MS)
            fetchForCurrentView()
        }
    }

    /**
     * Force-refresh the overlay (e.g. after network reconnect).
     */
    fun refresh() {
        lastRenderedBBox = null
        lastRenderedZoom = -1
        onMapMoved()
    }

    /**
     * Cleanup coroutines when activity is destroyed.
     */
    fun destroy() {
        fetchJob?.cancel()
        scope.cancel()
        clearOverlay()
    }

    // ═══════════════════════════════════════
    // Core: Fetch + Render Pipeline
    // ═══════════════════════════════════════

    private suspend fun fetchForCurrentView() {
        if (!isFetching.compareAndSet(false, true)) return

        try {
            val bbox = mapView.boundingBox
            val zoom = mapView.zoomLevelDouble.toInt()

            // Skip if same area + zoom
            if (isSameView(bbox, zoom)) {
                return
            }

            Log.d(TAG, "🗺️ Fetching vector data: zoom=$zoom, " +
                    "bbox=[${f(bbox.latSouth)},${f(bbox.lonWest)}]-[${f(bbox.latNorth)},${f(bbox.lonEast)}]")

            // Skip at very low zoom — too much data, tiles are enough
            if (zoom < 10) {
                clearOverlay()
                Log.d(TAG, "  Zoom $zoom < 10 — tiles only, no vector overlay")
                return
            }

            // Fetch from backend using the current bbox
            val result = withContext(Dispatchers.IO) {
                repository.fetchBBoxGraph(
                    south = bbox.latSouth,
                    west = bbox.lonWest,
                    north = bbox.latNorth,
                    east = bbox.lonEast
                )
            }

            if (result == null || result.graph.isEmpty()) {
                Log.d(TAG, "  No vector data for this area")
                return
            }

            Log.d(TAG, "  ✅ Got ${result.graph.nodeCount()} nodes, " +
                    "${result.graph.edgeCount()} edges (${result.source}, " +
                    "${result.sizeBytes / 1024}KB, ${result.decodeTimeMs}ms)")

            // Render on main thread
            withContext(Dispatchers.Main) {
                renderGraph(result.graph, zoom)
                lastRenderedBBox = bbox
                lastRenderedZoom = zoom
            }

        } catch (e: CancellationException) {
            // Normal — debounce cancelled
        } catch (e: Exception) {
            Log.w(TAG, "  ⚠️ Vector fetch failed: ${e.message}")
        } finally {
            isFetching.set(false)
        }
    }

    // ═══════════════════════════════════════
    // Rendering
    // ═══════════════════════════════════════

    private fun renderGraph(graph: Graph, zoom: Int) {
        clearOverlay()

        val nodeMap = mutableMapOf<String, Node>()
        for (node in graph.getAllNodes()) {
            nodeMap[node.id] = node
        }

        // Group edges by road type for batch styling
        val edgesByType = mutableMapOf<String, MutableList<Edge>>()
        for (node in graph.getAllNodes()) {
            for (edge in graph.getEdges(node.id)) {
                // Skip reverse edges (we already draw bidirectional)
                if (edge.from > edge.to && !edge.isOneWay) continue
                edgesByType.getOrPut(edge.roadType) { mutableListOf() }.add(edge)
            }
        }

        var totalRendered = 0

        // Render in order: residential (bottom) → motorway (top)
        // So motorways draw ON TOP of smaller roads
        val renderOrder = listOf(
            "residential", "unclassified", "service", "living_street",
            "footway", "path", "cycleway", "pedestrian", "track",
            "tertiary_link", "tertiary",
            "secondary_link", "secondary",
            "primary_link", "primary",
            "trunk_link", "trunk",
            "motorway_link", "motorway"
        )

        for (roadType in renderOrder) {
            val edges = edgesByType[roadType] ?: continue

            // At wide zoom, skip minor roads (they come from tiles anyway)
            if (zoom < 14 && roadType in listOf(
                    "residential", "unclassified", "service", "living_street",
                    "footway", "path", "cycleway", "pedestrian", "track", "steps"
                )) continue

            if (zoom < 12 && roadType in listOf("tertiary", "tertiary_link")) continue

            for (edge in edges) {
                if (totalRendered >= MAX_RENDER_EDGES) break

                val fromNode = nodeMap[edge.from] ?: continue
                val toNode = nodeMap[edge.to] ?: continue

                val style = getStyle(roadType)
                val points = listOf(
                    GeoPoint(fromNode.lat, fromNode.lng),
                    GeoPoint(toNode.lat, toNode.lng)
                )

                // Border (wider, darker line underneath)
                if (style.hasBorder) {
                    val border = Polyline().apply {
                        setPoints(points)
                        outlinePaint.color = style.borderColor
                        outlinePaint.strokeWidth = style.borderWidth
                        outlinePaint.isAntiAlias = true
                        outlinePaint.strokeCap = Paint.Cap.ROUND
                        outlinePaint.strokeJoin = Paint.Join.ROUND
                        outlinePaint.alpha = VECTOR_ALPHA
                    }
                    folderOverlay.add(border)
                }

                // Main line
                val polyline = Polyline().apply {
                    setPoints(points)
                    outlinePaint.color = style.color
                    outlinePaint.strokeWidth = style.width
                    outlinePaint.isAntiAlias = true
                    outlinePaint.strokeCap = Paint.Cap.ROUND
                    outlinePaint.strokeJoin = Paint.Join.ROUND
                    outlinePaint.alpha = VECTOR_ALPHA
                }
                folderOverlay.add(polyline)
                totalRendered++
            }
        }

        mapView.invalidate()
        Log.d(TAG, "  🎨 Rendered $totalRendered edges across ${edgesByType.keys.size} road types")
    }

    private fun clearOverlay() {
        folderOverlay.items.clear()
        mapView.invalidate()
    }

    // ═══════════════════════════════════════
    // Road Styling
    // ═══════════════════════════════════════

    private data class RoadStyle(
        val color: Int,
        val width: Float,
        val hasBorder: Boolean = false,
        val borderColor: Int = Color.BLACK,
        val borderWidth: Float = 0f
    )

    private fun getStyle(roadType: String): RoadStyle {
        return when (roadType) {
            "motorway", "motorway_link" -> RoadStyle(
                color = COLOR_MOTORWAY,
                width = WIDTH_MOTORWAY,
                hasBorder = true,
                borderColor = COLOR_MOTORWAY_BORDER,
                borderWidth = WIDTH_MOTORWAY_BORDER
            )
            "trunk", "trunk_link" -> RoadStyle(
                color = COLOR_MOTORWAY,
                width = WIDTH_MOTORWAY - 1f,
                hasBorder = true,
                borderColor = COLOR_MOTORWAY_BORDER,
                borderWidth = WIDTH_MOTORWAY_BORDER - 1.5f
            )
            "primary", "primary_link" -> RoadStyle(
                color = COLOR_PRIMARY,
                width = WIDTH_PRIMARY
            )
            "secondary", "secondary_link" -> RoadStyle(
                color = COLOR_SECONDARY,
                width = WIDTH_SECONDARY
            )
            "tertiary", "tertiary_link" -> RoadStyle(
                color = COLOR_TERTIARY,
                width = WIDTH_TERTIARY
            )
            else -> RoadStyle(
                color = COLOR_RESIDENTIAL,
                width = WIDTH_RESIDENTIAL
            )
        }
    }

    // ═══════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════

    /**
     * Check if the current view is close enough to the last rendered view
     * that we don't need to re-fetch.
     */
    private fun isSameView(bbox: BoundingBox, zoom: Int): Boolean {
        val lastBBox = lastRenderedBBox ?: return false
        if (zoom != lastRenderedZoom) return false

        // Check if current bbox is mostly within the last rendered bbox
        // (allow ~20% panning before re-fetching)
        val latRange = lastBBox.latNorth - lastBBox.latSouth
        val lngRange = lastBBox.lonEast - lastBBox.lonWest
        val threshold = 0.2 // 20% of bbox width

        return bbox.latSouth >= lastBBox.latSouth - latRange * threshold &&
                bbox.latNorth <= lastBBox.latNorth + latRange * threshold &&
                bbox.lonWest >= lastBBox.lonWest - lngRange * threshold &&
                bbox.lonEast <= lastBBox.lonEast + lngRange * threshold
    }

    private fun f(d: Double) = String.format("%.4f", d)
}

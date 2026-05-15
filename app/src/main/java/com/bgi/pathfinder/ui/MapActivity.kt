// app/src/main/java/com/bgi/pathfinder/ui/MapActivity.kt
package com.bgi.pathfinder.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.location.LocationManager
import android.os.Bundle
import android.telephony.SmsManager
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bgi.pathfinder.R
import com.bgi.pathfinder.algorithm.AStarAlgorithm
import com.bgi.pathfinder.map.HybridMapOverlay
import com.bgi.pathfinder.models.*
import com.bgi.pathfinder.network.LocalSearchService
import com.bgi.pathfinder.network.MapRepository
import com.bgi.pathfinder.utils.PolylineSimplifier
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay

class MapActivity : AppCompatActivity() {

    companion object {
        private const val LOCATION_PERMISSION_CODE = 1001
        private const val SMS_PERMISSION_CODE = 1002
        // ⚠️ CHANGE THIS to the actual emergency contact number!
        private const val SOS_PHONE_NUMBER = "YOUR_TEST_NUMBER"

        // ⚠️ Supabase Config — Get these from: https://supabase.com → Project Settings → API
        private const val SUPABASE_URL = "https://gyjdbqmdoofkywmnxhyc.supabase.co"
        private const val SUPABASE_ANON_KEY = "sb_publishable_qRe0N5vaN1_Ziqte5oLBEw_QT4NZHLA"

        // ⚠️ Tracking page URL — where your track.html is hosted
        // For testing via your backend: "http://<laptop-ip>:3000/track.html"
        // For production: "https://your-app.vercel.app/track.html"
        private const val TRACKING_BASE_URL = "http://10.217.209.31:3000/track.html"
    }

    // ═══════════════════════════════════════
    // Views
    // ═══════════════════════════════════════
    private lateinit var mapView: MapView
    private lateinit var etStartSearch: EditText
    private lateinit var etEndSearch: EditText
    private lateinit var rvStartResults: RecyclerView
    private lateinit var rvEndResults: RecyclerView
    private lateinit var btnFindRoutes: MaterialButton
    private lateinit var btnReset: MaterialButton
    private lateinit var loadingCard: MaterialCardView
    private lateinit var tvLoadingText: TextView
    private lateinit var bottomPanel: MaterialCardView
    private lateinit var tvRouteADist: TextView
    private lateinit var tvRouteATime: TextView
    private lateinit var tvRouteBDist: TextView
    private lateinit var tvRouteBTime: TextView
    private lateinit var fabMyLocation: FloatingActionButton
    private lateinit var fabSos: FloatingActionButton

    // SOS — Google Play Services Fused Location (most accurate)
    private lateinit var fusedLocationClient: FusedLocationProviderClient

    // SOS Live Tracking — Supabase Realtime Broadcast
    private var supabaseClient: com.bgi.pathfinder.network.SupabaseRealtimeClient? = null
    private var sosSessionId: String? = null
    private var isTracking = false
    private var locationCallback: com.google.android.gms.location.LocationCallback? = null

    // Adapters
    private lateinit var startAdapter: SearchResultAdapter
    private lateinit var endAdapter: SearchResultAdapter

    // State
    private var startPoint: GeoPoint? = null
    private var endPoint: GeoPoint? = null
    private var startSearchJob: Job? = null
    private var endSearchJob: Job? = null
    private var hasReceivedFirstFix = false

    // Repository — 100% LOCAL (Protobuf → JSON, no external APIs)
    private val mapRepository = MapRepository()

    // GPS / Location overlay
    private var locationOverlay: MyLocationNewOverlay? = null

    // Hybrid Map — Vector overlay on top of raster tiles
    private lateinit var hybridOverlay: HybridMapOverlay

    // Map overlays
    private val markers = mutableListOf<Marker>()
    private val routePolylines = mutableListOf<Polyline>()

    // ═══════════════════════════════════════
    // Lifecycle
    // ═══════════════════════════════════════

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // OSMDroid config — disable online tile downloads
        val config = Configuration.getInstance()
        config.load(applicationContext, getSharedPreferences("osmdroid_prefs", MODE_PRIVATE))
        config.userAgentValue = packageName

        setContentView(R.layout.activity_map)
        initViews()
        setupOfflineMap()
        setupSearch()
        setupMyLocationButton()
        checkBackendAvailability()
        requestLocationPermission()

        // SOS — Initialize FusedLocationProviderClient
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        btnFindRoutes.setOnClickListener { findRoutes() }
        btnReset.setOnClickListener { resetAll() }
        fabSos.setOnClickListener { handleSosClick() }

        // Show "Connecting..." spinner on startup
        showLoading("Connecting to local database...")
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
        locationOverlay?.enableMyLocation()
    }

    override fun onPause() {
        super.onPause()
        mapView.onPause()
        locationOverlay?.disableMyLocation()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::hybridOverlay.isInitialized) {
            hybridOverlay.destroy()
        }
    }

    // ═══════════════════════════════════════
    // View Initialization
    // ═══════════════════════════════════════

    private fun initViews() {
        mapView = findViewById(R.id.mapView)
        etStartSearch = findViewById(R.id.etStartSearch)
        etEndSearch = findViewById(R.id.etEndSearch)
        rvStartResults = findViewById(R.id.rvStartResults)
        rvEndResults = findViewById(R.id.rvEndResults)
        btnFindRoutes = findViewById(R.id.btnFindRoutes)
        btnReset = findViewById(R.id.btnReset)
        loadingCard = findViewById(R.id.loadingCard)
        tvLoadingText = findViewById(R.id.tvLoadingText)
        bottomPanel = findViewById(R.id.bottomPanel)
        tvRouteADist = findViewById(R.id.tvRouteADist)
        tvRouteATime = findViewById(R.id.tvRouteATime)
        tvRouteBDist = findViewById(R.id.tvRouteBDist)
        tvRouteBTime = findViewById(R.id.tvRouteBTime)
        fabMyLocation = findViewById(R.id.fabMyLocation)
        fabSos = findViewById(R.id.fabSos)
    }

    /**
     * Configure Hybrid Map:
     *
     * LAYER 0 — MAPNIK Raster Tiles (online, cached locally)
     *   Provides the visual "filler" so the map never looks empty.
     *   Includes terrain, labels, buildings, parks, etc.
     *
     * LAYER 1 — GPS Blue Dot
     *   User's current location.
     *
     * LAYER 2 — HybridMapOverlay (Local Vector Roads)
     *   Polylines fetched from our local Node.js backend.
     *   Bold, distinct colors (blue motorways, orange primary roads)
     *   that stand out ON TOP of the background tiles.
     *   LOD-aware: zoomed in = all roads, zoomed out = highways only.
     *
     * LAYER 3+ — Route polylines & markers (user actions)
     *
     * All ROUTING + SEARCH is still 100% LOCAL (MongoDB only).
     * Only the background tiles come from the internet.
     */
    private fun setupOfflineMap() {
        // ── Part 1: Background Raster Layer ──
        // MAPNIK tiles for visual map — cached locally by OSMDroid
        mapView.setTileSource(TileSourceFactory.MAPNIK)

        mapView.setMultiTouchControls(true)
        mapView.setBuiltInZoomControls(false)
        mapView.controller.setZoom(14.0)
        mapView.controller.setCenter(GeoPoint(28.6139, 77.2090)) // Delhi default

        mapView.isHorizontalMapRepetitionEnabled = false
        mapView.isVerticalMapRepetitionEnabled = false

        // Enable tile caching for offline use
        val config = Configuration.getInstance()
        config.tileFileSystemCacheMaxBytes = 200L * 1024 * 1024  // 200MB cache
        config.tileFileSystemCacheTrimBytes = 150L * 1024 * 1024 // Trim at 150MB

        // ── Part 2: Local Vector Overlay ──
        hybridOverlay = HybridMapOverlay(mapView, mapRepository)
        hybridOverlay.attach()

        // ── Part 3: Zoom & Scroll Listener ──
        // Triggers LOD-aware data fetching on map movement
        mapView.addMapListener(object : MapListener {
            override fun onScroll(event: ScrollEvent?): Boolean {
                hybridOverlay.onMapMoved()
                return false
            }

            override fun onZoom(event: ZoomEvent?): Boolean {
                hybridOverlay.onMapMoved()
                return false
            }
        })

        // Initial fetch after map is ready
        mapView.post {
            hybridOverlay.refresh()
        }
    }

    // ═══════════════════════════════════════
    // GPS — Location Permission + Blue Dot
    // ═══════════════════════════════════════

    /**
     * Request Fine Location permission at runtime (Android 6+).
     */
    private fun requestLocationPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ),
                LOCATION_PERMISSION_CODE
            )
        } else {
            enableGPS()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_PERMISSION_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                enableGPS()
            } else {
                Toast.makeText(this, "Location permission denied. Using default location.", Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * Enable GPS blue dot overlay and auto-center on first fix.
     */
    private fun enableGPS() {
        val gpsProvider = GpsMyLocationProvider(this)
        gpsProvider.addLocationSource(LocationManager.GPS_PROVIDER)
        gpsProvider.addLocationSource(LocationManager.NETWORK_PROVIDER)

        locationOverlay = MyLocationNewOverlay(gpsProvider, mapView).apply {
            enableMyLocation()

            // Blue dot icon
            setPersonIcon(createGpsDotBitmap())
            setDirectionIcon(createGpsDotBitmap())

            // Auto-center on FIRST GPS fix
            runOnFirstFix {
                runOnUiThread {
                    if (!hasReceivedFirstFix) {
                        hasReceivedFirstFix = true
                        val loc = myLocation
                        if (loc != null) {
                            val geoPoint = GeoPoint(loc.latitude, loc.longitude)
                            mapView.controller.animateTo(geoPoint, 18.0, 1500L)
                            Toast.makeText(
                                this@MapActivity,
                                "📍 Location found!",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            }
        }

        // Add GPS overlay BELOW route overlays
        mapView.overlays.add(0, locationOverlay)
        mapView.invalidate()
    }

    /**
     * Create a blue GPS dot bitmap (similar to Google Maps).
     */
    private fun createGpsDotBitmap(): Bitmap {
        val size = 48
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val cx = size / 2f

        // Outer glow (semi-transparent blue)
        canvas.drawCircle(cx, cx, 22f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#404285F4")
            style = Paint.Style.FILL
        })

        // White border
        canvas.drawCircle(cx, cx, 14f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        })

        // Blue center
        canvas.drawCircle(cx, cx, 10f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#4285F4")
            style = Paint.Style.FILL
        })

        return bmp
    }

    // ═══════════════════════════════════════
    // My Location Button
    // ═══════════════════════════════════════

    private fun setupMyLocationButton() {
        fabMyLocation.setOnClickListener {
            val loc = locationOverlay?.myLocation
            if (loc != null) {
                val geoPoint = GeoPoint(loc.latitude, loc.longitude)
                mapView.controller.animateTo(geoPoint, 18.0, 800L)
            } else {
                Toast.makeText(this, "Waiting for GPS fix...", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ═══════════════════════════════════════
    // Search — 100% LOCAL (no Nominatim)
    // ═══════════════════════════════════════

    private lateinit var startTextWatcher: TextWatcher
    private lateinit var endTextWatcher: TextWatcher

    private fun setupSearch() {
        startTextWatcher = createSearchWatcher { query ->
            startSearchJob?.cancel()
            startSearchJob = lifecycleScope.launch {
                delay(300)
                val loc = getUserLocation()
                val results = LocalSearchService.search(query, loc?.first, loc?.second)
                if (results.isNotEmpty() && etStartSearch.hasFocus()) {
                    startAdapter.updateResults(results)
                    rvStartResults.visibility = View.VISIBLE
                } else {
                    rvStartResults.visibility = View.GONE
                }
            }
        }

        endTextWatcher = createSearchWatcher { query ->
            endSearchJob?.cancel()
            endSearchJob = lifecycleScope.launch {
                delay(300)
                val loc = getUserLocation()
                val results = LocalSearchService.search(query, loc?.first, loc?.second)
                if (results.isNotEmpty() && etEndSearch.hasFocus()) {
                    endAdapter.updateResults(results)
                    rvEndResults.visibility = View.VISIBLE
                } else {
                    rvEndResults.visibility = View.GONE
                }
            }
        }

        // Start location search
        startAdapter = SearchResultAdapter { result ->
            startPoint = GeoPoint(result.lat, result.lon)
            
            // Temporarily remove watcher to prevent API call
            etStartSearch.removeTextChangedListener(startTextWatcher)
            etStartSearch.setText(result.displayName.take(50))
            etStartSearch.addTextChangedListener(startTextWatcher)
            
            etStartSearch.clearFocus()
            rvStartResults.visibility = View.GONE
            addMarker(startPoint!!, "START", Color.parseColor("#4CAF50"))
            mapView.controller.animateTo(startPoint)
            mapView.controller.setZoom(14.0)
            checkReady()
            hideKeyboard()
        }
        rvStartResults.layoutManager = LinearLayoutManager(this)
        rvStartResults.adapter = startAdapter
        etStartSearch.addTextChangedListener(startTextWatcher)

        // End location search
        endAdapter = SearchResultAdapter { result ->
            endPoint = GeoPoint(result.lat, result.lon)
            
            // Temporarily remove watcher to prevent API call
            etEndSearch.removeTextChangedListener(endTextWatcher)
            etEndSearch.setText(result.displayName.take(50))
            etEndSearch.addTextChangedListener(endTextWatcher)
            
            etEndSearch.clearFocus()
            rvEndResults.visibility = View.GONE
            addMarker(endPoint!!, "END", Color.parseColor("#F44336"))
            mapView.controller.animateTo(endPoint)
            checkReady()
            hideKeyboard()
        }
        rvEndResults.layoutManager = LinearLayoutManager(this)
        rvEndResults.adapter = endAdapter
        etEndSearch.addTextChangedListener(endTextWatcher)
    }

    /**
     * Get current user GPS location for proximity-based search.
     * Returns (lat, lng) pair or null if GPS not available.
     */
    private fun getUserLocation(): Pair<Double, Double>? {
        val loc = locationOverlay?.myLocation ?: return null
        return Pair(loc.latitude, loc.longitude)
    }

    private fun createSearchWatcher(onQuery: (String) -> Unit): TextWatcher {
        return object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString()?.trim() ?: ""
                if (query.length >= 3) onQuery(query)
            }
        }
    }

    private fun checkReady() {
        btnFindRoutes.isEnabled = (startPoint != null && endPoint != null)
    }

    // ═══════════════════════════════════════
    // Backend Health Check
    // ═══════════════════════════════════════

    private fun checkBackendAvailability() {
        lifecycleScope.launch {
            val healthy = mapRepository.isBackendHealthy()
            hideLoading()
            val msg = if (healthy) "✅ Backend connected (57.8M nodes)" else "❌ Backend unreachable — check hotspot"
            Toast.makeText(this@MapActivity, msg, Toast.LENGTH_SHORT).show()
        }
    }

    // ═══════════════════════════════════════
    // Routing Pipeline — 100% LOCAL
    // ═══════════════════════════════════════

    private fun findRoutes() {
        val start = startPoint ?: return
        val end = endPoint ?: return

        val distKm = org.osmdroid.util.GeoPoint(start.latitude, start.longitude)
            .distanceToAsDouble(GeoPoint(end.latitude, end.longitude)) / 1000.0
        if (distKm > 50) {
            Toast.makeText(this, "Points are ${String.format("%.1f", distKm)}km apart. Max ~50km with LOD.", Toast.LENGTH_LONG).show()
        }

        // DYNAMIC GRAPH FETCHING: Adjust padding based on distance
        // A short trip (2km) gets small padding (1.5), while a long trip (30km) gets larger padding (3.0) 
        // to ensure alternative routes outside the direct bounding box are fetched.
        val dynamicPadding = when {
            distKm > 20.0 -> 3.0
            distKm > 5.0 -> 2.5
            else -> 1.5
        }

        btnFindRoutes.isEnabled = false
        showLoading("Fetching road network...")

        lifecycleScope.launch {
            try {
                val result = mapRepository.fetchRouteGraph(
                    start.latitude, start.longitude,
                    end.latitude, end.longitude,
                    dynamicPadding
                )
                val graph = result.graph
                android.util.Log.d("MapActivity", "AStar Setup: Fetched graph with ${graph.nodeCount()} nodes and ${graph.edgeCount()} edges (padding=$dynamicPadding)")

                val sizeKB = result.sizeBytes / 1024
                showLoading("Via ${result.source} (${sizeKB}KB, ${result.decodeTimeMs}ms)")

                if (graph.isEmpty()) {
                    hideLoading()
                    Toast.makeText(this@MapActivity, "No road data found in this area!", Toast.LENGTH_LONG).show()
                    btnFindRoutes.isEnabled = true
                    return@launch
                }

                showLoading("${graph.nodeCount()} nodes, ${graph.edgeCount()} edges\nSnapping to roads...")

                val startNode = graph.findNearestNode(start.latitude, start.longitude)
                val endNode = graph.findNearestNode(end.latitude, end.longitude)

                if (startNode == null || endNode == null) {
                    hideLoading()
                    Toast.makeText(this@MapActivity, "Could not snap to road. Try closer points.", Toast.LENGTH_LONG).show()
                    btnFindRoutes.isEnabled = true
                    return@launch
                }

                // ── DEBUG OVERLAY: Draw small dots where it actually snapped ──
                val startSnap = Marker(mapView).apply {
                    position = GeoPoint(startNode.lat, startNode.lng)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    title = "Snapped Start"
                    icon = createCircleDrawable(Color.YELLOW, 12f, Color.BLACK, 3f)
                    setOnMarkerClickListener { _, _ -> false }
                }
                val endSnap = Marker(mapView).apply {
                    position = GeoPoint(endNode.lat, endNode.lng)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    title = "Snapped End"
                    icon = createCircleDrawable(Color.parseColor("#FFA500"), 12f, Color.BLACK, 3f)
                    setOnMarkerClickListener { _, _ -> false }
                }
                mapView.overlays.add(startSnap)
                mapView.overlays.add(endSnap)
                markers.add(startSnap)
                markers.add(endSnap)
                // ──────────────────────────────────────────────────────────────

                showLoading("Running A★ pathfinding...")

                val astar = AStarAlgorithm(graph)
                val routes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    astar.findMultipleRoutes(startNode.id, endNode.id)
                }

                hideLoading()

                if (routes.isEmpty() || !routes[0].isFound) {
                    Toast.makeText(this@MapActivity, "No path found between these points!", Toast.LENGTH_LONG).show()
                    btnFindRoutes.isEnabled = true
                    return@launch
                }

                drawRoutes(graph, routes)

            } catch (e: OutOfMemoryError) {
                hideLoading()
                Toast.makeText(this@MapActivity, "Area too large! Try closer points.", Toast.LENGTH_LONG).show()
                btnFindRoutes.isEnabled = true
                System.gc()
            } catch (e: Exception) {
                hideLoading()
                Toast.makeText(this@MapActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                btnFindRoutes.isEnabled = true
                e.printStackTrace()
            }
        }
    }

    // ═══════════════════════════════════════
    // Route Drawing
    // ═══════════════════════════════════════

    private fun drawRoutes(graph: Graph, routes: List<PathResult>) {
        // Route A — Blue (shortest)
        if (routes.isNotEmpty()) {
            val geoPoints = routes[0].path.mapNotNull { id ->
                graph.getNode(id)?.let { GeoPoint(it.lat, it.lng) }
            }
            drawPolyline(geoPoints, Color.parseColor("#42A5F5"), 8f, false)
            tvRouteADist.text = formatDistance(routes[0].totalDistanceMeters)
            tvRouteATime.text = "⏱ ${formatTime(routes[0].estimatedTimeMinutes)}"
        }

        // Route B — Red (weighted/alternative)
        if (routes.size >= 2) {
            val geoPoints = routes[1].path.mapNotNull { id ->
                graph.getNode(id)?.let { GeoPoint(it.lat, it.lng) }
            }
            drawPolyline(geoPoints, Color.parseColor("#EF5350"), 6f, true)
            tvRouteBDist.text = formatDistance(routes[1].totalDistanceMeters)
            tvRouteBTime.text = "⏱ ${formatTime(routes[1].estimatedTimeMinutes)}"
        } else {
            tvRouteBDist.text = "Same as shortest"
            tvRouteBTime.text = ""
        }

        bottomPanel.visibility = View.VISIBLE
        mapView.invalidate()
    }

    private fun drawPolyline(points: List<GeoPoint>, color: Int, width: Float, dashed: Boolean) {
        // Douglas-Peucker simplification — reduces GPU load drastically
        // e.g., 5000 points → ~200 points with identical visual appearance
        val zoom = mapView.zoomLevelDouble
        val simplified = PolylineSimplifier.simplifyForZoom(points, zoom)

        android.util.Log.d("MapActivity", "Polyline: ${points.size} → ${simplified.size} points (zoom=$zoom)")

        val polyline = Polyline().apply {
            setPoints(simplified)
            outlinePaint.color = color
            outlinePaint.strokeWidth = width
            outlinePaint.isAntiAlias = true
            outlinePaint.strokeCap = Paint.Cap.ROUND
            outlinePaint.strokeJoin = Paint.Join.ROUND
            outlinePaint.alpha = 220
            if (dashed) {
                outlinePaint.pathEffect = DashPathEffect(floatArrayOf(25f, 12f), 0f)
            }
        }
        mapView.overlays.add(polyline)
        routePolylines.add(polyline)
    }

    // ═══════════════════════════════════════
    // Markers
    // ═══════════════════════════════════════

    private fun addMarker(point: GeoPoint, title: String, color: Int) {
        val marker = Marker(mapView)
        marker.position = point
        marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        marker.title = title
        marker.icon = createCircleDrawable(color, 24f, Color.WHITE, 5f)
        marker.setOnMarkerClickListener { _, _ -> false }
        mapView.overlays.add(marker)
        markers.add(marker)
        mapView.invalidate()
    }

    private fun createCircleDrawable(color: Int, radius: Float, stroke: Int, sw: Float): android.graphics.drawable.Drawable {
        val size = (radius * 2 + sw * 2).toInt()
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val cx = size / 2f
        canvas.drawCircle(cx, cx, radius + sw / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = stroke; style = Paint.Style.FILL })
        canvas.drawCircle(cx, cx, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.FILL })
        return BitmapDrawable(resources, bmp)
    }

    // ═══════════════════════════════════════
    // Reset / Utilities
    // ═══════════════════════════════════════

    private fun resetAll() {
        startPoint = null; endPoint = null
        
        // Prevent API calls when clearing text
        etStartSearch.removeTextChangedListener(startTextWatcher)
        etEndSearch.removeTextChangedListener(endTextWatcher)
        
        etStartSearch.text.clear(); etEndSearch.text.clear()
        
        etStartSearch.addTextChangedListener(startTextWatcher)
        etEndSearch.addTextChangedListener(endTextWatcher)
        
        etStartSearch.clearFocus(); etEndSearch.clearFocus()
        
        rvStartResults.visibility = View.GONE; rvEndResults.visibility = View.GONE
        markers.forEach { mapView.overlays.remove(it) }; markers.clear()
        routePolylines.forEach { mapView.overlays.remove(it) }; routePolylines.clear()
        bottomPanel.visibility = View.GONE
        btnFindRoutes.isEnabled = false
        hideKeyboard()
        mapView.invalidate()
    }

    private fun showLoading(text: String) { tvLoadingText.text = text; loadingCard.visibility = View.VISIBLE }
    private fun hideLoading() { loadingCard.visibility = View.GONE }
    private fun hideKeyboard() { (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(currentFocus?.windowToken, 0) }

    private fun formatDistance(m: Double): String = if (m >= 1000) String.format("%.1f km", m / 1000) else String.format("%.0f m", m)
    private fun formatTime(min: Double): String {
        val h = min.toInt() / 60; val m = min.toInt() % 60
        return if (h > 0) "${h}h ${m}min" else "${m} min"
    }

    // ═══════════════════════════════════════
    // SOS Live Tracking (Supabase Realtime)
    // ═══════════════════════════════════════

    /**
     * SOS button toggles live tracking on/off:
     *   1st click → Start tracking + send SMS with live tracking link
     *   2nd click → Stop tracking
     */
    private fun handleSosClick() {
        if (isTracking) {
            stopSosTracking()
            return
        }

        // ── Permission checks ──
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.SEND_SMS), SMS_PERMISSION_CODE
            )
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), LOCATION_PERMISSION_CODE
            )
            return
        }

        // ── GPS check ──
        val locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            Toast.makeText(this, "⚠️ GPS is turned off! Enable GPS for SOS.", Toast.LENGTH_LONG).show()
            return
        }

        startSosTracking()
    }

    /**
     * Start live SOS tracking:
     *  1. Generate unique session UUID
     *  2. Connect to Supabase Realtime channel
     *  3. Start GPS updates every 5 seconds
     *  4. Send SMS with live tracking link
     */
    private fun startSosTracking() {
        val sessionId = java.util.UUID.randomUUID().toString().take(8)
        sosSessionId = sessionId
        val channelName = "sos-$sessionId"

        android.util.Log.d("SOS", "🆘 Starting live tracking | Session: $channelName")

        // 1. Connect to Supabase Realtime
        supabaseClient = com.bgi.pathfinder.network.SupabaseRealtimeClient(
            SUPABASE_URL, SUPABASE_ANON_KEY
        )
        supabaseClient?.connect(channelName)

        // 2. Start location updates every 5 seconds
        val locationRequest = com.google.android.gms.location.LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY, 5000L
        ).setMinUpdateIntervalMillis(3000L).build()

        locationCallback = object : com.google.android.gms.location.LocationCallback() {
            override fun onLocationResult(result: com.google.android.gms.location.LocationResult) {
                val loc = result.lastLocation ?: return
                supabaseClient?.broadcastLocation(
                    loc.latitude, loc.longitude, System.currentTimeMillis()
                )
            }
        }

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED
        ) {
            fusedLocationClient.requestLocationUpdates(
                locationRequest, locationCallback!!, mainLooper
            )
        }

        // 3. Update UI — FAB turns green to show "tracking active"
        isTracking = true
        fabSos.backgroundTintList = android.content.res.ColorStateList.valueOf(
            Color.parseColor("#4CAF50")
        )
        Toast.makeText(this, "🆘 Live tracking started! Sending SMS...", Toast.LENGTH_SHORT).show()

        // 4. Send SMS with live tracking link (after short delay for WebSocket to connect)
        android.os.Handler(mainLooper).postDelayed({
            sendTrackingSms(sessionId)
        }, 2000)
    }

    /**
     * Stop live SOS tracking.
     */
    private fun stopSosTracking() {
        // Stop location updates
        locationCallback?.let { fusedLocationClient.removeLocationUpdates(it) }
        locationCallback = null

        // Disconnect Supabase WebSocket
        supabaseClient?.disconnect()
        supabaseClient = null
        sosSessionId = null

        // Reset UI — FAB back to red
        isTracking = false
        fabSos.backgroundTintList = android.content.res.ColorStateList.valueOf(
            Color.parseColor("#FF0000")
        )
        Toast.makeText(this, "✅ SOS tracking stopped.", Toast.LENGTH_SHORT).show()
        android.util.Log.d("SOS", "🔌 Live tracking stopped")
    }

    /**
     * Send SMS with live tracking URL.
     */
    private fun sendTrackingSms(sessionId: String) {
        try {
            val trackingLink = "$TRACKING_BASE_URL?id=sos-$sessionId"
            val message = "EMERGENCY! I need help. Track my LIVE location: $trackingLink"

            @Suppress("DEPRECATION")
            val smsManager = SmsManager.getDefault()
            val parts = smsManager.divideMessage(message)
            smsManager.sendMultipartTextMessage(
                SOS_PHONE_NUMBER, null, parts, null, null
            )

            android.util.Log.d("SOS", "✅ SMS sent: $trackingLink")
            Toast.makeText(this, "🆘 SOS Alert Sent! Link: $trackingLink", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            android.util.Log.e("SOS", "SMS failed: ${e.message}")
            Toast.makeText(this, "❌ Failed to send SMS: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Clean up tracking if activity is destroyed
        if (isTracking) stopSosTracking()
        if (::hybridOverlay.isInitialized) hybridOverlay.destroy()
    }
}


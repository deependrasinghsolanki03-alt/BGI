// app/src/main/java/com/bgi/pathfinder/ui/MapActivity.kt
package com.bgi.pathfinder.ui

import android.content.Intent
import android.os.Build

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
import com.bgi.pathfinder.models.SearchResult
import com.bgi.pathfinder.network.OrsClient
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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.config.Configuration
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
        private const val NOTIFICATION_PERMISSION_CODE = 1003
        private const val SOS_PHONE_NUMBER = "YOUR_TEST_NUMBER"
        // ⚠️ CHANGE to your deployed Vercel URL
        private const val TRACKING_BASE_URL = "https://bgi-rust.vercel.app"
    }

    private var isTracking = false
    private var currentSosId: String? = null

    // Views
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

    // SOS — GPS
    private lateinit var fusedLocationClient: FusedLocationProviderClient

    // Adapters & State
    private lateinit var startAdapter: SearchResultAdapter
    private lateinit var endAdapter: SearchResultAdapter
    private lateinit var startTextWatcher: TextWatcher
    private lateinit var endTextWatcher: TextWatcher
    private var startPoint: GeoPoint? = null
    private var endPoint: GeoPoint? = null
    private var startSearchJob: Job? = null
    private var endSearchJob: Job? = null
    private var locationOverlay: MyLocationNewOverlay? = null
    private val markers = mutableListOf<Marker>()
    private val routePolylines = mutableListOf<Polyline>()

    // ═══════════════════════════════════════
    // Lifecycle
    // ═══════════════════════════════════════

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val config = Configuration.getInstance()
        config.load(applicationContext, getSharedPreferences("osmdroid_prefs", MODE_PRIVATE))
        config.userAgentValue = packageName

        setContentView(R.layout.activity_map)
        initViews()
        setupMap()
        setupSearch()
        setupMyLocationButton()

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        btnFindRoutes.setOnClickListener { hideKeyboard(); findRoutes() }
        btnReset.setOnClickListener { resetAll() }
        fabSos.setOnClickListener { handleSosClick() }

        requestLocationPermission()
    }

    override fun onResume() { super.onResume(); mapView.onResume(); locationOverlay?.enableMyLocation() }
    override fun onPause() { super.onPause(); mapView.onPause(); locationOverlay?.disableMyLocation() }

    // ═══════════════════════════════════════
    // View Init
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

    // ═══════════════════════════════════════
    // Map Setup — OSM Cloud Tiles
    // ═══════════════════════════════════════

    private fun setupMap() {
        mapView.setTileSource(TileSourceFactory.MAPNIK)
        mapView.setMultiTouchControls(true)
        mapView.controller.setZoom(14.0)
        // Default center: Gwalior
        mapView.controller.setCenter(GeoPoint(26.2183, 78.1828))

        // GPS blue dot
        val provider = GpsMyLocationProvider(applicationContext)
        provider.locationUpdateMinTime = 5000
        locationOverlay = MyLocationNewOverlay(provider, mapView)
        locationOverlay?.enableMyLocation()
        locationOverlay?.runOnFirstFix {
            runOnUiThread {
                locationOverlay?.myLocation?.let { loc ->
                    mapView.controller.animateTo(GeoPoint(loc.latitude, loc.longitude))
                }
            }
        }
        mapView.overlays.add(locationOverlay)
    }

    private fun setupMyLocationButton() {
        fabMyLocation.setOnClickListener {
            val loc = locationOverlay?.myLocation
            if (loc != null) {
                mapView.controller.animateTo(GeoPoint(loc.latitude, loc.longitude))
                mapView.controller.setZoom(16.0)
            } else {
                Toast.makeText(this, "GPS not available", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ═══════════════════════════════════════
    // Search — ORS Geocoding (Cloud)
    // ═══════════════════════════════════════

    private fun setupSearch() {
        startTextWatcher = createSearchWatcher { query ->
            startSearchJob?.cancel()
            startSearchJob = lifecycleScope.launch {
                delay(400)
                val results = searchOrs(query)
                if (results.isNotEmpty() && etStartSearch.hasFocus()) {
                    startAdapter.updateResults(results)
                    rvStartResults.visibility = View.VISIBLE
                } else { rvStartResults.visibility = View.GONE }
            }
        }
        endTextWatcher = createSearchWatcher { query ->
            endSearchJob?.cancel()
            endSearchJob = lifecycleScope.launch {
                delay(400)
                val results = searchOrs(query)
                if (results.isNotEmpty() && etEndSearch.hasFocus()) {
                    endAdapter.updateResults(results)
                    rvEndResults.visibility = View.VISIBLE
                } else { rvEndResults.visibility = View.GONE }
            }
        }

        startAdapter = SearchResultAdapter { result ->
            startPoint = GeoPoint(result.lat, result.lon)
            etStartSearch.removeTextChangedListener(startTextWatcher)
            etStartSearch.setText(result.displayName.take(60))
            etStartSearch.addTextChangedListener(startTextWatcher)
            etStartSearch.clearFocus()
            rvStartResults.visibility = View.GONE
            addMarker(startPoint!!, "START", Color.parseColor("#4CAF50"))
            mapView.controller.animateTo(startPoint)
            mapView.controller.setZoom(14.0)
            checkReady(); hideKeyboard()
        }
        rvStartResults.layoutManager = LinearLayoutManager(this)
        rvStartResults.adapter = startAdapter
        etStartSearch.addTextChangedListener(startTextWatcher)

        endAdapter = SearchResultAdapter { result ->
            endPoint = GeoPoint(result.lat, result.lon)
            etEndSearch.removeTextChangedListener(endTextWatcher)
            etEndSearch.setText(result.displayName.take(60))
            etEndSearch.addTextChangedListener(endTextWatcher)
            etEndSearch.clearFocus()
            rvEndResults.visibility = View.GONE
            addMarker(endPoint!!, "END", Color.parseColor("#F44336"))
            mapView.controller.animateTo(endPoint)
            checkReady(); hideKeyboard()
        }
        rvEndResults.layoutManager = LinearLayoutManager(this)
        rvEndResults.adapter = endAdapter
        etEndSearch.addTextChangedListener(endTextWatcher)
    }

    /** Call ORS Geocoding API */
    private suspend fun searchOrs(query: String): List<SearchResult> {
        return try {
            val loc = locationOverlay?.myLocation
            val response = OrsClient.api.geocodeSearch(
                query = query, limit = 8,
                focusLat = loc?.latitude, focusLng = loc?.longitude,
                radiusKm = if (loc != null) 50 else null
            )
            if (!response.isSuccessful) return emptyList()
            val json = JSONObject(response.body()!!.string())
            val features = json.getJSONArray("features")
            val results = mutableListOf<SearchResult>()
            for (i in 0 until features.length()) {
                val f = features.getJSONObject(i)
                val coords = f.getJSONObject("geometry").getJSONArray("coordinates")
                val props = f.getJSONObject("properties")
                results.add(SearchResult(
                    displayName = props.optString("label", "Unknown"),
                    lat = coords.getDouble(1),
                    lon = coords.getDouble(0),
                    type = props.optString("layer", "")
                ))
            }
            results
        } catch (e: Exception) {
            android.util.Log.e("ORS", "Geocode failed: ${e.message}")
            emptyList()
        }
    }

    private fun createSearchWatcher(onQuery: (String) -> Unit): TextWatcher {
        return object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString()?.trim() ?: ""
                if (q.length >= 3) onQuery(q)
            }
        }
    }

    private fun checkReady() {
        btnFindRoutes.isEnabled = (startPoint != null && endPoint != null)
    }

    // ═══════════════════════════════════════
    // Routing — ORS Directions (Cloud)
    // ═══════════════════════════════════════

    private fun findRoutes() {
        val start = startPoint ?: return
        val end = endPoint ?: return
        btnFindRoutes.isEnabled = false
        showLoading("Fetching routes from cloud...")

        lifecycleScope.launch {
            try {
                // Build ORS directions request body
                val body = JSONObject().apply {
                    put("coordinates", JSONArray().apply {
                        put(JSONArray().apply { put(start.longitude); put(start.latitude) })
                        put(JSONArray().apply { put(end.longitude); put(end.latitude) })
                    })
                    put("alternative_routes", JSONObject().apply {
                        put("target_count", 3)
                        put("share_factor", 0.6)
                        put("weight_factor", 1.4)
                    })
                }.toString()

                val requestBody = body.toRequestBody("application/json".toMediaType())
                val response = OrsClient.api.getDirections(body = requestBody)

                if (!response.isSuccessful) {
                    hideLoading()
                    val errBody = response.errorBody()?.string() ?: "Unknown error"
                    Toast.makeText(this@MapActivity, "Route API error: ${response.code()}", Toast.LENGTH_LONG).show()
                    android.util.Log.e("ORS", "Directions error: $errBody")
                    btnFindRoutes.isEnabled = true
                    return@launch
                }

                val json = JSONObject(response.body()!!.string())
                val features = json.getJSONArray("features")

                if (features.length() == 0) {
                    hideLoading()
                    Toast.makeText(this@MapActivity, "No routes found!", Toast.LENGTH_LONG).show()
                    btnFindRoutes.isEnabled = true
                    return@launch
                }

                hideLoading()

                // Draw routes
                val routeColors = intArrayOf(
                    Color.parseColor("#42A5F5"),  // Blue — primary
                    Color.parseColor("#EF5350"),  // Red — alternative
                    Color.parseColor("#66BB6A")   // Green — alternative 2
                )

                for (i in 0 until features.length()) {
                    val feature = features.getJSONObject(i)
                    val coords = feature.getJSONObject("geometry").getJSONArray("coordinates")
                    val props = feature.getJSONObject("properties")
                    val summary = props.getJSONObject("summary")
                    val distMeters = summary.getDouble("distance")
                    val durationSec = summary.getDouble("duration")

                    // Decode coordinate array into GeoPoints
                    val geoPoints = mutableListOf<GeoPoint>()
                    for (j in 0 until coords.length()) {
                        val c = coords.getJSONArray(j)
                        geoPoints.add(GeoPoint(c.getDouble(1), c.getDouble(0)))
                    }

                    val color = routeColors[i.coerceAtMost(routeColors.size - 1)]
                    val width = if (i == 0) 8f else 5f
                    val dashed = i > 0
                    drawPolyline(geoPoints, color, width, dashed)

                    // Update bottom panel
                    when (i) {
                        0 -> {
                            tvRouteADist.text = formatDistance(distMeters)
                            tvRouteATime.text = "⏱ ${formatTime(durationSec / 60.0)}"
                        }
                        1 -> {
                            tvRouteBDist.text = formatDistance(distMeters)
                            tvRouteBTime.text = "⏱ ${formatTime(durationSec / 60.0)}"
                        }
                    }
                }

                // Zoom to fit route
                if (features.length() > 0) {
                    val bbox = json.optJSONArray("bbox")
                    if (bbox != null && bbox.length() >= 4) {
                        val sw = GeoPoint(bbox.getDouble(1), bbox.getDouble(0))
                        val ne = GeoPoint(bbox.getDouble(3), bbox.getDouble(2))
                        mapView.zoomToBoundingBox(
                            org.osmdroid.util.BoundingBox(ne.latitude, ne.longitude, sw.latitude, sw.longitude),
                            true, 100
                        )
                    }
                }

                bottomPanel.visibility = View.VISIBLE
                mapView.invalidate()

            } catch (e: Exception) {
                hideLoading()
                Toast.makeText(this@MapActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                android.util.Log.e("ORS", "Route error", e)
                btnFindRoutes.isEnabled = true
            }
        }
    }

    // ═══════════════════════════════════════
    // Safety Layer — Overlay custom reports
    // ═══════════════════════════════════════

    /**
     * Add a safety report marker on the map.
     * Call this to overlay danger zones or safe spots on top of ORS routes.
     * Example: addSafetyMarker(GeoPoint(26.22, 78.18), "Dark alley — avoid at night", true)
     */
    fun addSafetyMarker(point: GeoPoint, label: String, isDanger: Boolean) {
        val color = if (isDanger) Color.parseColor("#FF1744") else Color.parseColor("#00E676")
        val marker = Marker(mapView)
        marker.position = point
        marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        marker.title = label
        marker.icon = createCircleDrawable(color, 16f, Color.WHITE, 3f)
        marker.setOnMarkerClickListener { m, _ ->
            Toast.makeText(this, m.title, Toast.LENGTH_SHORT).show()
            true
        }
        mapView.overlays.add(marker)
        markers.add(marker)
        mapView.invalidate()
    }

    // ═══════════════════════════════════════
    // Drawing
    // ═══════════════════════════════════════

    private fun drawPolyline(points: List<GeoPoint>, color: Int, width: Float, dashed: Boolean) {
        val polyline = Polyline().apply {
            setPoints(points)
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
        hideKeyboard(); mapView.invalidate()
    }

    private fun requestLocationPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), LOCATION_PERMISSION_CODE)
        }
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
    // SOS — Foreground Service + SMS
    // ═══════════════════════════════════════

    /**
     * Toggle SOS tracking:
     *   1st click → Start tracking + send SMS with live link
     *   2nd click → Stop tracking
     */
    private fun handleSosClick() {
        if (isTracking) {
            stopSosTracking()
            return
        }

        // Check SMS permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.SEND_SMS), SMS_PERMISSION_CODE)
            return
        }
        // Check location permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), LOCATION_PERMISSION_CODE)
            return
        }
        // Check notification permission (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_PERMISSION_CODE)
                return
            }
        }
        // Check GPS
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            Toast.makeText(this, "⚠️ GPS is off! Enable GPS for SOS.", Toast.LENGTH_LONG).show()
            return
        }

        startSosTracking()
    }

    private fun startSosTracking() {
        val sosId = java.util.UUID.randomUUID().toString().take(8)
        currentSosId = sosId

        // 1. Start foreground service
        val serviceIntent = Intent(this, com.bgi.pathfinder.service.SOSService::class.java).apply {
            putExtra(com.bgi.pathfinder.service.SOSService.EXTRA_SOS_ID, sosId)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        // 2. Update UI — FAB turns green
        isTracking = true
        fabSos.backgroundTintList = android.content.res.ColorStateList.valueOf(
            android.graphics.Color.parseColor("#4CAF50")
        )

        // 3. Send SMS with live tracking link
        try {
            val trackingUrl = "$TRACKING_BASE_URL/?id=$sosId"
            val msg = "EMERGENCY! I need help. Track me LIVE: $trackingUrl"
            @Suppress("DEPRECATION") val sms = SmsManager.getDefault()
            val parts = sms.divideMessage(msg)
            sms.sendMultipartTextMessage(SOS_PHONE_NUMBER, null, parts, null, null)
            Toast.makeText(this, "🆘 SOS Active! SMS sent with live tracking link.", Toast.LENGTH_LONG).show()
            android.util.Log.d("SOS", "✅ SMS sent: $trackingUrl")
        } catch (e: Exception) {
            Toast.makeText(this, "❌ SMS failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopSosTracking() {
        // Stop foreground service
        val serviceIntent = Intent(this, com.bgi.pathfinder.service.SOSService::class.java).apply {
            action = com.bgi.pathfinder.service.SOSService.ACTION_STOP
        }
        startService(serviceIntent)

        // Reset UI
        isTracking = false
        currentSosId = null
        fabSos.backgroundTintList = android.content.res.ColorStateList.valueOf(
            android.graphics.Color.parseColor("#FF0000")
        )
        Toast.makeText(this, "✅ SOS tracking stopped.", Toast.LENGTH_SHORT).show()
    }
}

// app/src/main/java/com/bgi/pathfinder/service/SOSService.kt
package com.bgi.pathfinder.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.bgi.pathfinder.R
import com.bgi.pathfinder.network.SupabaseClient
import com.bgi.pathfinder.ui.MapActivity
import com.google.android.gms.location.*
import kotlinx.coroutines.*

/**
 * Foreground Service for SOS Live Tracking.
 *
 * Runs with a persistent notification so Android doesn't kill it.
 * Gets GPS updates every 5 seconds and pushes each location
 * to Supabase 'locations' table via REST API.
 *
 * Started from MapActivity with an sos_id UUID.
 * Stopped when user taps "Stop Tracking" or the notification action.
 */
class SOSService : Service() {

    companion object {
        const val EXTRA_SOS_ID = "sos_id"
        const val ACTION_STOP = "com.bgi.pathfinder.STOP_SOS"
        private const val TAG = "SOSService"
        private const val CHANNEL_ID = "sos_tracking_channel"
        private const val NOTIFICATION_ID = 9911
        private const val LOCATION_INTERVAL_MS = 5000L
        private const val MAX_TRACKING_MS = 5 * 60 * 1000L  // 5 minutes auto-stop
    }

    private lateinit var fusedClient: FusedLocationProviderClient
    private var locationCallback: LocationCallback? = null
    private var sosId: String = ""
    private val autoStopHandler = android.os.Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        fusedClient = LocationServices.getFusedLocationProviderClient(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Handle stop action from notification
        if (intent?.action == ACTION_STOP) {
            Log.d(TAG, "🛑 Stop action received")
            stopSelf()
            return START_NOT_STICKY
        }

        sosId = intent?.getStringExtra(EXTRA_SOS_ID) ?: run {
            Log.e(TAG, "No sos_id provided, stopping")
            stopSelf()
            return START_NOT_STICKY
        }

        Log.d(TAG, "🆘 SOS Service started | ID: $sosId")

        // Start foreground with persistent notification
        startForeground(NOTIFICATION_ID, buildNotification())

        // Start GPS tracking
        startLocationUpdates()

        // Auto-stop after 5 minutes
        autoStopHandler.postDelayed({
            Log.d(TAG, "⏰ 5 min timeout — auto-stopping SOS")
            stopSelf()
        }, MAX_TRACKING_MS)

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        // Cancel auto-stop timer
        autoStopHandler.removeCallbacksAndMessages(null)
        // Stop GPS updates
        locationCallback?.let { fusedClient.removeLocationUpdates(it) }
        locationCallback = null
        serviceScope.cancel()
        Log.d(TAG, "🔌 SOS Service destroyed")
    }

    // ═══════════════════════════════════════
    // GPS Location Updates
    // ═══════════════════════════════════════

    private fun startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "Location permission not granted")
            stopSelf()
            return
        }

        val request = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY, LOCATION_INTERVAL_MS
        ).setMinUpdateIntervalMillis(3000L).build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                Log.d(TAG, "📍 Location: ${loc.latitude}, ${loc.longitude}")

                // Push to Supabase in background
                serviceScope.launch {
                    val success = SupabaseClient.insertLocation(
                        loc.latitude, loc.longitude, sosId
                    )
                    if (success) Log.d(TAG, "✅ Pushed to Supabase")
                    else Log.e(TAG, "❌ Supabase push failed")
                }
            }
        }

        fusedClient.requestLocationUpdates(request, locationCallback!!, Looper.getMainLooper())
    }

    // ═══════════════════════════════════════
    // Notification
    // ═══════════════════════════════════════

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "SOS Live Tracking",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Shows when SOS live location tracking is active"
                setShowBadge(true)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        // Tap notification → open MapActivity
        val openIntent = Intent(this, MapActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPending = PendingIntent.getActivity(
            this, 0, openIntent, PendingIntent.FLAG_IMMUTABLE
        )

        // "Stop Tracking" action button
        val stopIntent = Intent(this, SOSService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this, 1, stopIntent, PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🆘 SOS Active")
            .setContentText("Your live location is being shared...")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setColor(0xFFFF0000.toInt())
            .setOngoing(true)
            .setContentIntent(openPending)
            .addAction(android.R.drawable.ic_delete, "Stop Tracking", stopPending)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }
}

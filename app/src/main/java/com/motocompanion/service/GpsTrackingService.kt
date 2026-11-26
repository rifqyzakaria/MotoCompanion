package com.motocompanion.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.Granularity
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.motocompanion.App
import com.motocompanion.R
import com.motocompanion.gps.GpsStabilizer
import com.motocompanion.gps.StabilizedGpsData
import com.motocompanion.ui.MainActivity

/**
 * Layanan Live GPS Kooperatif berbasis Google Play Services FusedLocationProvider:
 * - Berbagi stream lokasi secara harmonis (multiplexed) dengan Honda RoadSync & Google Maps
 *   tanpa me-reset chip GNSS, tanpa mengunci antena Wi-Fi/BLE 2.4GHz, dan tanpa membebani Headunit.
 * - Menjaga sinyal GPS tetap hangat & live di latar belakang saat layar HP terkunci di saku.
 */
class GpsTrackingService : Service(), LocationListener {

    private val tag = "MotoGpsService"

    companion object {
        const val BROADCAST_GPS_UPDATE = "com.motocompanion.GPS_UPDATE"

        const val EXTRA_SPEED_KMH = "speed_kmh"
        const val EXTRA_BEARING = "bearing"
        const val EXTRA_CARDINAL = "cardinal"
        const val EXTRA_ALTITUDE = "altitude"
        const val EXTRA_ACCURACY = "accuracy"
        const val EXTRA_SATELLITES = "satellites"
        const val EXTRA_LOCKED = "gps_locked"

        @Volatile
        var latestGpsData: StabilizedGpsData? = null
            private set

        @Volatile
        var isServiceRunning: Boolean = false
            private set
    }

    private val binder = LocalBinder()
    private lateinit var locationManager: LocationManager
    private var fusedLocationClient: FusedLocationProviderClient? = null
    private val gpsStabilizer = GpsStabilizer()

    private var wakeLock: PowerManager.WakeLock? = null
    private var isTrackingStarted = false
    private var lastNotificationUpdateMs = 0L

    private val fusedLocationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: return
            handleIncomingLocation(loc)
        }
    }

    inner class LocalBinder : Binder() {
        fun getService(): GpsTrackingService = this@GpsTrackingService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        if (!hasAnyLocationPermission()) {
            Log.w(tag, "Izin lokasi belum diberikan, menghentikan GpsTrackingService dengan aman.")
            stopSelf()
            return
        }

        isServiceRunning = true
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        if (!startForegroundSafely(getString(R.string.gps_notif_locking))) {
            isServiceRunning = false
            stopSelf()
            return
        }

        acquireGentleWakeLock()
        startCooperativeFusedGps()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!hasAnyLocationPermission()) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!isTrackingStarted) {
            startCooperativeFusedGps()
        }
        return START_STICKY
    }

    private fun startForegroundSafely(statusText: String): Boolean {
        return try {
            val notification = buildNotification(statusText)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(1002, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } else {
                startForeground(1002, notification)
            }
            true
        } catch (e: Exception) {
            Log.e(tag, "Tidak dapat memulai foreground location service dari latar belakang: ${e.message}")
            false
        }
    }

    private fun acquireGentleWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (wakeLock == null && pm != null) {
                wakeLock = pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "MotoCompanion::LiveGpsKeepAlive"
                ).apply {
                    setReferenceCounted(false)
                    acquire(4 * 60 * 60 * 1000L)
                }
            }
        } catch (e: Exception) {
            Log.w(tag, "Gagal mengakuisisi WakeLock GPS: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun startCooperativeFusedGps() {
        if (isTrackingStarted) return
        isTrackingStarted = true

        // Gunakan satu stream kooperatif FusedLocationProvider (1000ms stabil) yang berbagi bus lokasi
        // bersama Google Maps & Honda RoadSync tanpa mengganggu koneksi BLE atau mereset GNSS
        try {
            val client = LocationServices.getFusedLocationProviderClient(this)
            fusedLocationClient = client

            client.lastLocation.addOnSuccessListener { loc ->
                if (loc != null) handleIncomingLocation(loc)
            }

            val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
                .setMinUpdateIntervalMillis(500L)
                .setMinUpdateDistanceMeters(0.0f)
                .setGranularity(Granularity.GRANULARITY_FINE)
                .setWaitForAccurateLocation(false)
                .build()

            client.requestLocationUpdates(request, fusedLocationCallback, Looper.getMainLooper())
            Log.i(tag, "Cooperative Fused Live GPS aktif (1000ms, ramah RoadSync & Google Maps)")
        } catch (e: Exception) {
            Log.w(tag, "FusedLocationProvider tidak tersedia, fallback ke GPS_PROVIDER standar: ${e.message}")
            try {
                if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    locationManager.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER,
                        1000L,
                        0.0f,
                        this,
                        Looper.getMainLooper()
                    )
                }
            } catch (ex: Exception) {
                Log.e(tag, "Fallback GPS_PROVIDER gagal: ${ex.message}")
            }
        }
    }

    override fun onLocationChanged(location: Location) {
        handleIncomingLocation(location)
    }

    private fun handleIncomingLocation(location: Location) {
        val stabilized = gpsStabilizer.processLocation(location)
        latestGpsData = stabilized

        val intent = Intent(BROADCAST_GPS_UPDATE).apply {
            setPackage(packageName)
            putExtra(EXTRA_SPEED_KMH, stabilized.speedKmh)
            putExtra(EXTRA_BEARING, stabilized.stabilizedBearing)
            putExtra(EXTRA_CARDINAL, stabilized.cardinalDirection)
            putExtra(EXTRA_ALTITUDE, stabilized.altitudeMeters)
            putExtra(EXTRA_ACCURACY, stabilized.accuracyMeters)
            putExtra(EXTRA_SATELLITES, stabilized.satellitesInFix)
            putExtra(EXTRA_LOCKED, stabilized.isRealtimeLocked)
        }
        sendBroadcast(intent)

        // Update notifikasi halus setiap 3 detik agar tidak membebani NotificationManager RoadSync
        val now = System.currentTimeMillis()
        if (now - lastNotificationUpdateMs >= 3000L) {
            lastNotificationUpdateMs = now
            val speedInt = stabilized.speedKmh.toInt()
            val accInt = stabilized.accuracyMeters.toInt().coerceAtLeast(1)
            val lockText = if (stabilized.isRealtimeLocked) {
                getString(R.string.gps_notif_locked_format, speedInt, accInt, stabilized.cardinalDirection)
            } else {
                getString(R.string.gps_notif_searching_format, accInt)
            }

            try {
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(1002, buildNotification(lockText))
            } catch (_: Exception) {}
        }
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, App.CHANNEL_GPS_TRACKER)
            .setContentTitle(getString(R.string.gps_notif_title))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_bike)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .build()
    }

    private fun hasAnyLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    override fun onDestroy() {
        super.onDestroy()
        isServiceRunning = false
        isTrackingStarted = false
        try {
            fusedLocationClient?.removeLocationUpdates(fusedLocationCallback)
        } catch (_: Exception) {}
        try {
            locationManager.removeUpdates(this)
        } catch (_: Exception) {}
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {}
    }

    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}
    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
}

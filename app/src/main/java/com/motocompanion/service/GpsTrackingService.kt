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
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.CurrentLocationRequest
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
 * Layanan GPS Realtime Latar Belakang berbasis Google Play Services FusedLocationProvider (mesin Google Maps)
 * dikombinasikan dengan pengunci satelit GNSS hardware langsung:
 * - Memastikan GPS langsung lock dalam hitungan detik saat terhubung ke Headunit motor.
 * - Mencegah jeda/batching lokasi saat layar HP mati di saku sehingga notifikasi Turn-by-Turn tidak pernah telat.
 * - Menjaga presisi jalur carrier-phase GNSS agar Maps / RoadSync tidak terus-menerus "Mengubah rute".
 */
class GpsTrackingService : Service(), LocationListener {

    private val tag = "MotoGpsService"

    companion object {
        const val BROADCAST_GPS_UPDATE = "com.motocompanion.GPS_UPDATE"
        const val ACTION_BOOST_NAVIGATION = "com.motocompanion.ACTION_BOOST_NAVIGATION"

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
    private val mainHandler = Handler(Looper.getMainLooper())

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    @Volatile
    private var satellitesUsedInFix: Int = 0
    private var isTrackingStarted = false
    private var isTurboNavigationMode = false
    private var lastNotificationUpdateMs = 0L

    private val fusedLocationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: return
            handleIncomingLocation(loc)
        }
    }

    private val gnssStatusCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            var usedCount = 0
            for (i in 0 until status.satelliteCount) {
                if (status.usedInFix(i)) {
                    usedCount++
                }
            }
            satellitesUsedInFix = usedCount
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

        acquireRealtimeLocks()
        startRealtimeGpsEngine(turboMode = false)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!hasAnyLocationPermission()) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_BOOST_NAVIGATION && !isTurboNavigationMode) {
            Log.i(tag, "🚀 Navigasi Google Maps / RoadSync terdeteksi: Mengaktifkan Turbo Realtime GPS (250ms)")
            isTurboNavigationMode = true
            restartFusedLocationRequest(turboMode = true)
        } else if (!isTrackingStarted) {
            startRealtimeGpsEngine(turboMode = isTurboNavigationMode)
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

    private fun acquireRealtimeLocks() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (wakeLock == null && pm != null) {
                wakeLock = pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "MotoCompanion::RealtimeGpsWakeLock"
                ).apply {
                    setReferenceCounted(false)
                    acquire(6 * 60 * 60 * 1000L) // Batas aman maksimal 6 jam berkendara nonstop
                }
            }
        } catch (e: Exception) {
            Log.w(tag, "Gagal mengakuisisi WakeLock GPS: ${e.message}")
        }

        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            if (wifiLock == null && wm != null) {
                val lockMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                } else {
                    @Suppress("DEPRECATION")
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF
                }
                wifiLock = wm.createWifiLock(lockMode, "MotoCompanion::RealtimeMapsWifiLock").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
        } catch (e: Exception) {
            Log.w(tag, "Gagal mengakuisisi WifiLock: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun startRealtimeGpsEngine(turboMode: Boolean) {
        if (isTrackingStarted) return
        isTrackingStarted = true

        // 1. Injeksi A-GPS XTRA & waktu NTP agar cold-lock satelit berlangsung instan (< 2 detik) saat connect Headunit
        try {
            locationManager.sendExtraCommand(LocationManager.GPS_PROVIDER, "force_xtra_injection", Bundle())
            locationManager.sendExtraCommand(LocationManager.GPS_PROVIDER, "force_time_injection", Bundle())
        } catch (_: Exception) {}

        // 2. Daftarkan callback status satelit GNSS untuk mematikan power-saving duty-cycling antena GPS
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                locationManager.registerGnssStatusCallback(ContextCompat.getMainExecutor(this), gnssStatusCallback)
            } else {
                @Suppress("DEPRECATION")
                locationManager.registerGnssStatusCallback(gnssStatusCallback, mainHandler)
            }
        } catch (e: Exception) {
            Log.w(tag, "Gagal mendaftarkan GnssStatusCallback: ${e.message}")
        }

        // 3. Aktifkan Google Play Services FusedLocationProviderClient (Engine Realtime Google Maps, 0ms batching delay)
        try {
            val client = LocationServices.getFusedLocationProviderClient(this)
            fusedLocationClient = client

            // Ambil lokasi instan detik ini juga agar tidak menunggu interval pertama
            val currentReq = CurrentLocationRequest.Builder()
                .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                .setMaxUpdateAgeMillis(0L)
                .setGranularity(Granularity.GRANULARITY_FINE)
                .build()
            client.getCurrentLocation(currentReq, null)
                .addOnSuccessListener { loc ->
                    if (loc != null) handleIncomingLocation(loc)
                }

            restartFusedLocationRequest(turboMode)
        } catch (e: Exception) {
            Log.e(tag, "FusedLocationProvider gagal diinisialisasi, mengandalkan GPS_PROVIDER murni: ${e.message}")
        }

        // 4. Pin Hardware GPS_PROVIDER secara paralel agar chip GNSS tetap terkunci di terowongan/bawah jalan layang
        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    500L,
                    0.0f,
                    this,
                    Looper.getMainLooper()
                )
            }
        } catch (e: Exception) {
            Log.e(tag, "Gagal meminta update hardware GPS_PROVIDER: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun restartFusedLocationRequest(turboMode: Boolean) {
        val client = fusedLocationClient ?: return
        try {
            client.removeLocationUpdates(fusedLocationCallback)
            val intervalMs = if (turboMode) 250L else 500L
            val minIntervalMs = if (turboMode) 150L else 250L

            val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
                .setMinUpdateIntervalMillis(minIntervalMs)
                .setMaxUpdateDelayMillis(0L) // Nol penundaan batching: krusial agar notifikasi Turn-by-Turn tepat waktu!
                .setMinUpdateDistanceMeters(0.0f)
                .setGranularity(Granularity.GRANULARITY_FINE)
                .setWaitForAccurateLocation(false)
                .build()

            client.requestLocationUpdates(request, fusedLocationCallback, Looper.getMainLooper())
            Log.i(tag, "Google Maps Fused Realtime Location aktif (interval=${intervalMs}ms, maxDelay=0ms)")
        } catch (e: Exception) {
            Log.e(tag, "Gagal memperbarui FusedLocationRequest: ${e.message}")
        }
    }

    override fun onLocationChanged(location: Location) {
        handleIncomingLocation(location)
    }

    private fun handleIncomingLocation(location: Location) {
        val stabilized = gpsStabilizer.processLocation(location, satellitesUsedInFix)
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

        // Throttle pembaruan notifikasi foreground ke maks 1x tiap 1.5 detik agar ringan di sistem
        val now = System.currentTimeMillis()
        if (now - lastNotificationUpdateMs >= 1500L) {
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
            locationManager.unregisterGnssStatusCallback(gnssStatusCallback)
        } catch (_: Exception) {}
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {}
        try {
            if (wifiLock?.isHeld == true) wifiLock?.release()
        } catch (_: Exception) {}
    }

    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}
    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
}

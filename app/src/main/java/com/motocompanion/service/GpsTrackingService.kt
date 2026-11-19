package com.motocompanion.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.motocompanion.App
import com.motocompanion.R
import com.motocompanion.gps.GpsStabilizer
import com.motocompanion.gps.StabilizedGpsData
import com.motocompanion.ui.HudActivity

class GpsTrackingService : Service(), LocationListener {

    private val tag = "MotoGpsService"

    companion object {
        const val BROADCAST_GPS_UPDATE = "com.motocompanion.GPS_UPDATE"
        const val EXTRA_SPEED_KMH = "speed_kmh"
        const val EXTRA_BEARING = "bearing"
        const val EXTRA_CARDINAL = "cardinal"
        const val EXTRA_ALTITUDE = "altitude"
        const val EXTRA_ACCURACY = "accuracy"

        var latestGpsData: StabilizedGpsData? = null
            private set
    }

    private val binder = LocalBinder()
    private lateinit var locationManager: LocationManager
    private val gpsStabilizer = GpsStabilizer()

    inner class LocalBinder : Binder() {
        fun getService(): GpsTrackingService = this@GpsTrackingService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        startForeground(1002, buildNotification("Menunggu sinyal GPS motor..."))
        requestLocationUpdates()
    }

    @SuppressLint("MissingPermission")
    private fun requestLocationUpdates() {
        try {
            // Pembaruan lokasi cepat (setiap 500ms atau 0 meter perubahan) untuk respon speedometer akurat
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                500L,
                0.0f,
                this
            )
        } catch (e: Exception) {
            Log.e(tag, "Gagal meminta update GPS: ${e.message}")
        }
    }

    override fun onLocationChanged(location: Location) {
        val stabilized = gpsStabilizer.processLocation(location)
        latestGpsData = stabilized

        // Broadcast data ke Activity yang sedang aktif
        val intent = Intent(BROADCAST_GPS_UPDATE).apply {
            putExtra(EXTRA_SPEED_KMH, stabilized.speedKmh)
            putExtra(EXTRA_BEARING, stabilized.stabilizedBearing)
            putExtra(EXTRA_CARDINAL, stabilized.cardinalDirection)
            putExtra(EXTRA_ALTITUDE, stabilized.altitudeMeters)
            putExtra(EXTRA_ACCURACY, stabilized.accuracyMeters)
        }
        sendBroadcast(intent)

        // Perbarui notifikasi foreground
        val speedInt = stabilized.speedKmh.toInt()
        val text = "Kecepatan: $speedInt km/j | Arah: ${stabilized.cardinalDirection}"
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        notificationManager.notify(1002, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = Intent(this, HudActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, openIntent, PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, App.CHANNEL_GPS_TRACKER)
            .setContentTitle("Moto HUD Aktif")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        locationManager.removeUpdates(this)
    }

    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}
    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
}

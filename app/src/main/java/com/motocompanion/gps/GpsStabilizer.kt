package com.motocompanion.gps

import android.location.Location
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

class GpsStabilizer {

    companion object {
        private const val MIN_SPEED_THRESHOLD_KMH = 3.5f // Di bawah kecepatan ini, kunci bearing (cegah putar-putar di lampu merah)
        private const val SMOOTHING_ALPHA = 0.35f        // Faktor kehalusan filter putaran
    }

    private var smoothedSin = 0.0
    private var smoothedCos = 1.0
    private var lastValidBearing = 0.0f
    private var hasInitialHeading = false

    /**
     * Memproses lokasi GPS dan menghasilkan kecepatan (km/jam) serta sudut arah yang stabil
     */
    fun processLocation(location: Location): StabilizedGpsData {
        val speedKmh = location.speed * 3.6f // meter/detik ke km/jam
        var bearing = lastValidBearing

        // Jika motor bergerak cukup cepat dan GPS memiliki data arah pergerakan
        if (location.hasBearing() && speedKmh >= MIN_SPEED_THRESHOLD_KMH) {
            val rawBearing = (location.bearing + 360f) % 360f

            if (!hasInitialHeading) {
                val rad = Math.toRadians(rawBearing.toDouble())
                smoothedSin = sin(rad)
                smoothedCos = cos(rad)
                lastValidBearing = rawBearing
                hasInitialHeading = true
                bearing = rawBearing
            } else {
                // Circular Exponential Moving Average untuk filter anti-jitter
                val rad = Math.toRadians(rawBearing.toDouble())
                val newSin = sin(rad)
                val newCos = cos(rad)

                smoothedSin = (1 - SMOOTHING_ALPHA) * smoothedSin + SMOOTHING_ALPHA * newSin
                smoothedCos = (1 - SMOOTHING_ALPHA) * smoothedCos + SMOOTHING_ALPHA * newCos

                val smoothedRad = atan2(smoothedSin, smoothedCos)
                var smoothedDeg = Math.toDegrees(smoothedRad).toFloat()
                if (smoothedDeg < 0) smoothedDeg += 360f

                lastValidBearing = smoothedDeg
                bearing = smoothedDeg
            }
        }

        val cardinal = getCardinalDirection(bearing)

        return StabilizedGpsData(
            speedKmh = speedKmh,
            stabilizedBearing = bearing,
            cardinalDirection = cardinal,
            altitudeMeters = location.altitude,
            accuracyMeters = location.accuracy,
            latitude = location.latitude,
            longitude = location.longitude
        )
    }

    private fun getCardinalDirection(bearing: Float): String {
        return when (((bearing + 22.5f) % 360f / 45f).toInt()) {
            0 -> "U (Utara)"
            1 -> "TL (Timur Laut)"
            2 -> "T (Timur)"
            3 -> "TG (Tenggara)"
            4 -> "S (Selatan)"
            5 -> "BD (Barat Daya)"
            6 -> "B (Barat)"
            7 -> "BL (Barat Laut)"
            else -> "U"
        }
    }
}

data class StabilizedGpsData(
    val speedKmh: Float,
    val stabilizedBearing: Float,
    val cardinalDirection: String,
    val altitudeMeters: Double,
    val accuracyMeters: Float,
    val latitude: Double,
    val longitude: Double
)

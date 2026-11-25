package com.motocompanion.gps

import android.location.Location
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Engine Stabilisasi GPS Realtime & Anti-Rerouting (Anti-"Mengubah Rute"):
 * 1. Menolak lonjakan sinyal multipath / cell-tower kasar (outlier rejection) agar titik lokasi tidak
 *    melompat ke jalan sebelah yang menyebabkan Google Maps / RoadSync sering "Mengubah rute".
 * 2. Mengunci arah kompas (heading lock) saat berhenti di lampu merah / merayap (< 4.5 km/jam) agar
 *    peta tidak berputar 180 derajat.
 * 3. Filter sudut adaptif: sangat responsif saat menikung di persimpangan (agar Turn-by-Turn tidak telat),
 *    dan sangat stabil saat melaju lurus.
 */
class GpsStabilizer {

    companion object {
        private const val MIN_SPEED_THRESHOLD_KMH = 4.5f // Kunci arah di bawah kecepatan ini (cegah putar balik palsu)
        private const val CRUISE_SMOOTHING_ALPHA = 0.30f // Kehalusan tinggi saat jalan lurus
        private const val TURN_SMOOTHING_ALPHA = 0.70f   // Respons instan saat berbelok di simpangan
        private const val TURN_ANGLE_THRESHOLD_DEG = 18f // Deteksi manuver belokan
        private const val MAX_GOOD_ACCURACY_M = 12.0f
        private const val OUTLIER_ACCURACY_SPIKE_M = 28.0f
        private const val MAX_OUTLIER_HOLD_MS = 3500L
    }

    private var smoothedSin = 0.0
    private var smoothedCos = 1.0
    private var lastValidBearing = 0.0f
    private var hasInitialHeading = false

    private var lastGoodLocation: Location? = null
    private var lastGoodFixTimeMs: Long = 0L
    private var smoothedSpeedKmh: Float = 0f

    /**
     * Memproses lokasi GPS realtime dan menghasilkan posisi, kecepatan, serta arah vektor yang stabil
     */
    fun processLocation(location: Location, satellitesInFix: Int = 0): StabilizedGpsData {
        val nowMs = System.currentTimeMillis()
        val prevGood = lastGoodLocation

        // 1. Filter Anti-Multipath / Anti-Teleport (Cegah "Mengubah Rute" akibat lonjakan sinyal sesaat)
        val effectiveLocation = if (
            prevGood != null &&
            prevGood.accuracy <= MAX_GOOD_ACCURACY_M &&
            location.accuracy > OUTLIER_ACCURACY_SPIKE_M &&
            (nowMs - lastGoodFixTimeMs) < MAX_OUTLIER_HOLD_MS
        ) {
            prevGood
        } else {
            if (location.accuracy <= 20.0f || prevGood == null) {
                lastGoodLocation = location
                lastGoodFixTimeMs = nowMs
            }
            location
        }

        val rawSpeedKmh = if (effectiveLocation.hasSpeed()) {
            (effectiveLocation.speed * 3.6f).coerceAtLeast(0f)
        } else {
            0f
        }

        // Haluskan kecepatan namun langsung turun ke 0 jika motor berhenti
        smoothedSpeedKmh = if (rawSpeedKmh < 1.2f) {
            0f
        } else {
            0.65f * rawSpeedKmh + 0.35f * smoothedSpeedKmh
        }

        var bearing = lastValidBearing

        // 2. Update vektor arah hanya jika motor benar-benar melaju di atas ambang batas & akurasi memadai
        if (effectiveLocation.hasBearing() && smoothedSpeedKmh >= MIN_SPEED_THRESHOLD_KMH && effectiveLocation.accuracy <= 25f) {
            val rawBearing = (effectiveLocation.bearing + 360f) % 360f

            if (!hasInitialHeading) {
                val rad = Math.toRadians(rawBearing.toDouble())
                smoothedSin = sin(rad)
                smoothedCos = cos(rad)
                lastValidBearing = rawBearing
                hasInitialHeading = true
                bearing = rawBearing
            } else {
                // Hitung selisih sudut terpendek untuk mendeteksi manuver belokan (Turn-by-Turn)
                var angleDelta = abs(rawBearing - lastValidBearing) % 360f
                if (angleDelta > 180f) angleDelta = 360f - angleDelta

                val alpha = if (angleDelta >= TURN_ANGLE_THRESHOLD_DEG) {
                    TURN_SMOOTHING_ALPHA
                } else {
                    CRUISE_SMOOTHING_ALPHA
                }

                val rad = Math.toRadians(rawBearing.toDouble())
                val newSin = sin(rad)
                val newCos = cos(rad)

                smoothedSin = (1 - alpha) * smoothedSin + alpha * newSin
                smoothedCos = (1 - alpha) * smoothedCos + alpha * newCos

                val smoothedRad = atan2(smoothedSin, smoothedCos)
                var smoothedDeg = Math.toDegrees(smoothedRad).toFloat()
                if (smoothedDeg < 0) smoothedDeg += 360f

                lastValidBearing = smoothedDeg
                bearing = smoothedDeg
            }
        }

        val cardinal = getCardinalDirection(bearing)
        val isLocked = effectiveLocation.accuracy in 0.1f..15.0f

        return StabilizedGpsData(
            speedKmh = smoothedSpeedKmh,
            stabilizedBearing = bearing,
            cardinalDirection = cardinal,
            altitudeMeters = effectiveLocation.altitude,
            accuracyMeters = effectiveLocation.accuracy,
            latitude = effectiveLocation.latitude,
            longitude = effectiveLocation.longitude,
            satellitesInFix = satellitesInFix,
            isRealtimeLocked = isLocked
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
    val longitude: Double,
    val satellitesInFix: Int = 0,
    val isRealtimeLocked: Boolean = false
)

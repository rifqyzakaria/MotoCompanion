package com.motocompanion

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.motocompanion.tts.TtsManager

class App : Application() {

    companion object {
        const val CHANNEL_TTS_ALERTS = "moto_tts_alerts"
        const val CHANNEL_MEDIA_SERVICE = "moto_media_service"
        const val CHANNEL_GPS_TRACKER = "moto_gps_tracker"
        const val CHANNEL_COMPANION = "moto_companion_status"

        lateinit var instance: App
            private set
    }

    lateinit var ttsManager: TtsManager
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        createNotificationChannels()
        ttsManager = TtsManager(this)
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // TTS Announcements Channel
            val ttsChannel = NotificationChannel(
                CHANNEL_TTS_ALERTS,
                "Pemberitahuan Suara (Intercom)",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Peringatan suara panggilan WhatsApp dan pesan masuk ke headset"
                setSound(null, null)
            }

            // Media Playback Service Channel
            val mediaChannel = NotificationChannel(
                CHANNEL_MEDIA_SERVICE,
                "Pemutar Musik Motor",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Status kontrol pemutar musik stang motor"
            }

            // GPS Tracker Channel
            val gpsChannel = NotificationChannel(
                CHANNEL_GPS_TRACKER,
                "GPS Speedometer & Stabilizer",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Tracking GPS real-time untuk speedometer dan anti-pusing peta"
            }

            // Companion Mode Status Channel
            val companionChannel = NotificationChannel(
                CHANNEL_COMPANION,
                "Pendamping Honda RoadSync",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Status koneksi pendamping ke motor Honda PCX & RoadSync"
            }

            notificationManager.createNotificationChannels(listOf(ttsChannel, mediaChannel, gpsChannel, companionChannel))
        }
    }
}

package com.motocompanion.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import com.motocompanion.R
import com.motocompanion.databinding.ActivityHudBinding
import com.motocompanion.service.GpsTrackingService
import com.motocompanion.util.MediaControlDispatcher
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HudActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHudBinding
    private val clockHandler = Handler(Looper.getMainLooper())
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    private val clockRunnable = object : Runnable {
        override fun run() {
            binding.tvHudClock.text = timeFormat.format(Date())
            updateHudMediaInfo()
            clockHandler.postDelayed(this, 1000)
        }
    }

    private val gpsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == GpsTrackingService.BROADCAST_GPS_UPDATE) {
                val speed = intent.getFloatExtra(GpsTrackingService.EXTRA_SPEED_KMH, 0f)
                val bearing = intent.getFloatExtra(GpsTrackingService.EXTRA_BEARING, 0f)
                val cardinal = intent.getStringExtra(GpsTrackingService.EXTRA_CARDINAL) ?: "U"
                val altitude = intent.getDoubleExtra(GpsTrackingService.EXTRA_ALTITUDE, 0.0)
                val accuracy = intent.getFloatExtra(GpsTrackingService.EXTRA_ACCURACY, 0f)

                updateHud(speed, bearing, cardinal, altitude, accuracy)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHudBinding.inflate(layoutInflater)
        setContentView(binding.root)

        enableImmersiveMode()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        binding.btnCloseHud.setOnClickListener {
            finish()
        }

        // Kontrol Media Cepat di Layar HUD
        binding.btnHudPlayPause.setOnClickListener {
            MediaControlDispatcher.dispatchMediaAction(this, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            clockHandler.postDelayed({ updateHudMediaInfo() }, 300)
        }

        binding.btnHudNext.setOnClickListener {
            MediaControlDispatcher.dispatchMediaAction(this, KeyEvent.KEYCODE_MEDIA_NEXT)
            clockHandler.postDelayed({ updateHudMediaInfo() }, 300)
        }

        binding.btnHudPrev.setOnClickListener {
            MediaControlDispatcher.dispatchMediaAction(this, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            clockHandler.postDelayed({ updateHudMediaInfo() }, 300)
        }

        clockHandler.post(clockRunnable)
    }

    override fun onResume() {
        super.onResume()
        enableImmersiveMode()
        updateHudMediaInfo()

        val filter = IntentFilter(GpsTrackingService.BROADCAST_GPS_UPDATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(gpsReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(gpsReceiver, filter)
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(gpsReceiver)
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        clockHandler.removeCallbacks(clockRunnable)
    }

    private fun updateHud(speed: Float, bearing: Float, cardinal: String, altitude: Double, accuracy: Float) {
        // Kecepatan
        binding.tvSpeedValue.text = speed.toInt().toString()

        // Jarum Kompas Anti-Pusing (Rotasi needle ke bearing pergerakan)
        binding.ivCompassNeedle.animate()
            .rotation(bearing)
            .setDuration(300)
            .start()

        // Teks Arah & Derajat
        binding.tvCardinalDirection.text = cardinal
        binding.tvBearingDegrees.text = getString(R.string.hud_bearing_locked, bearing.toInt())

        // Informasi Tambahan
        binding.tvAltitude.text = getString(R.string.hud_altitude_format, altitude.toInt())
        binding.tvGpsAccuracy.text = getString(R.string.hud_gps_accuracy_format, accuracy)
    }

    private fun updateHudMediaInfo() {
        val track = MediaControlDispatcher.getActiveTrackInfo(this)
        if (track != null && track.title.isNotBlank()) {
            val status = if (track.isPlaying) "▶" else "⏸"
            binding.tvActivePlayerHud.text = "$status ${track.title} (${track.appName})"
            binding.btnHudPlayPause.text = if (track.isPlaying) "⏸" else "▶"
        } else {
            val activePlayer = MediaControlDispatcher.getSelectedName(this)
            binding.tvActivePlayerHud.text = getString(R.string.hud_player_format, activePlayer)
        }
    }

    private fun enableImmersiveMode() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_FULLSCREEN
                )
    }
}

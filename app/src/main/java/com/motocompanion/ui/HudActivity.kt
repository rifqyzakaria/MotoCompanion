package com.motocompanion.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.Surface
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

class HudActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var binding: ActivityHudBinding
    private val clockHandler = Handler(Looper.getMainLooper())
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    private lateinit var sensorManager: SensorManager
    private var rotationVectorSensor: Sensor? = null
    private var accelerometer: Sensor? = null
    private var magnetometer: Sensor? = null

    private val rotationMatrix = FloatArray(9)
    private val remappedMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)

    private val lastAccelerometer = FloatArray(3)
    private val lastMagnetometer = FloatArray(3)
    private var lastAccelerometerSet = false
    private var lastMagnetometerSet = false

    private var currentFilteredAzimuth = 0f
    private var currentSpeedKmh = 0f
    private var currentGpsBearing = 0f

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
                val altitude = intent.getDoubleExtra(GpsTrackingService.EXTRA_ALTITUDE, 0.0)
                val accuracy = intent.getFloatExtra(GpsTrackingService.EXTRA_ACCURACY, 0f)

                currentSpeedKmh = speed
                currentGpsBearing = bearing

                updateSpeedAndStats(speed, altitude, accuracy)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHudBinding.inflate(layoutInflater)
        setContentView(binding.root)

        enableImmersiveMode()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        initSensors()

        binding.btnCloseHud.setOnClickListener {
            finish()
        }

        binding.btnHudPlayPause.setOnClickListener {
            MediaControlDispatcher.dispatchMediaAction(this, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            clockHandler.postDelayed({ updateHudMediaInfo() }, 250)
        }

        binding.btnHudNext.setOnClickListener {
            MediaControlDispatcher.dispatchMediaAction(this, KeyEvent.KEYCODE_MEDIA_NEXT)
            clockHandler.postDelayed({ updateHudMediaInfo() }, 250)
        }

        binding.btnHudPrev.setOnClickListener {
            MediaControlDispatcher.dispatchMediaAction(this, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            clockHandler.postDelayed({ updateHudMediaInfo() }, 250)
        }

        clockHandler.post(clockRunnable)
    }

    private fun initSensors() {
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        rotationVectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (rotationVectorSensor == null) {
            accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        }
    }

    override fun onResume() {
        super.onResume()
        enableImmersiveMode()
        updateHudMediaInfo()

        if (rotationVectorSensor != null) {
            sensorManager.registerListener(this, rotationVectorSensor, SensorManager.SENSOR_DELAY_UI)
        } else {
            accelerometer?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
            magnetometer?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        }

        val filter = IntentFilter(GpsTrackingService.BROADCAST_GPS_UPDATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(gpsReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(gpsReceiver, filter)
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
        try {
            unregisterReceiver(gpsReceiver)
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        clockHandler.removeCallbacks(clockRunnable)
    }

    override fun onSensorChanged(event: SensorEvent) {
        var rawAzimuthDeg: Float? = null

        if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
            rawAzimuthDeg = calculateAzimuth(rotationMatrix)
        } else if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            System.arraycopy(event.values, 0, lastAccelerometer, 0, event.values.size)
            lastAccelerometerSet = true
        } else if (event.sensor.type == Sensor.TYPE_MAGNETIC_FIELD) {
            System.arraycopy(event.values, 0, lastMagnetometer, 0, event.values.size)
            lastMagnetometerSet = true
        }

        if (rawAzimuthDeg == null && lastAccelerometerSet && lastMagnetometerSet) {
            if (SensorManager.getRotationMatrix(rotationMatrix, null, lastAccelerometer, lastMagnetometer)) {
                rawAzimuthDeg = calculateAzimuth(rotationMatrix)
            }
        }

        if (rawAzimuthDeg != null) {
            updateCompassDisplay(rawAzimuthDeg)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun calculateAzimuth(matrix: FloatArray): Float {
        @Suppress("DEPRECATION")
        val rotation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display?.rotation ?: Surface.ROTATION_90
        } else {
            windowManager.defaultDisplay.rotation
        }

        when (rotation) {
            Surface.ROTATION_90 -> {
                SensorManager.remapCoordinateSystem(
                    matrix,
                    SensorManager.AXIS_Y,
                    SensorManager.AXIS_MINUS_X,
                    remappedMatrix
                )
            }
            Surface.ROTATION_270 -> {
                SensorManager.remapCoordinateSystem(
                    matrix,
                    SensorManager.AXIS_MINUS_Y,
                    SensorManager.AXIS_X,
                    remappedMatrix
                )
            }
            Surface.ROTATION_180 -> {
                SensorManager.remapCoordinateSystem(
                    matrix,
                    SensorManager.AXIS_MINUS_X,
                    SensorManager.AXIS_MINUS_Y,
                    remappedMatrix
                )
            }
            else -> {
                SensorManager.remapCoordinateSystem(
                    matrix,
                    SensorManager.AXIS_X,
                    SensorManager.AXIS_Y,
                    remappedMatrix
                )
            }
        }

        SensorManager.getOrientation(remappedMatrix, orientationAngles)
        var azimuth = Math.toDegrees(orientationAngles[0].toDouble()).toFloat()
        if (azimuth < 0) azimuth += 360f
        return azimuth
    }

    private fun updateCompassDisplay(sensorAzimuth: Float) {
        val isUsingGps = currentSpeedKmh > 15f && currentGpsBearing > 0f
        val targetHeading = if (isUsingGps) currentGpsBearing else sensorAzimuth

        var diff = (targetHeading - currentFilteredAzimuth) % 360f
        if (diff > 180f) diff -= 360f
        if (diff < -180f) diff += 360f

        currentFilteredAzimuth = ((currentFilteredAzimuth + diff * 0.25f) % 360f + 360f) % 360f
        binding.ivCompassNeedle.rotation = -currentFilteredAzimuth

        val headingInt = currentFilteredAzimuth.toInt()
        val modeLabel = if (isUsingGps) getString(R.string.hud_compass_mode_gps) else getString(R.string.hud_compass_mode_sensor)
        binding.tvBearingDegrees.text = String.format(Locale.getDefault(), "%03d° · %s", headingInt, modeLabel)
        binding.tvCardinalDirection.text = getCardinal(currentFilteredAzimuth)
    }

    private fun updateSpeedAndStats(speed: Float, altitude: Double, accuracy: Float) {
        binding.tvSpeedValue.text = speed.toInt().toString()
        binding.tvAltitude.text = getString(R.string.hud_altitude_format, altitude.toInt())
        binding.tvGpsAccuracy.text = getString(R.string.hud_gps_accuracy_format, accuracy)
    }

    private fun getCardinal(bearing: Float): String {
        return when (((bearing + 22.5f) % 360f / 45f).toInt()) {
            0 -> getString(R.string.cardinal_n)
            1 -> getString(R.string.cardinal_ne)
            2 -> getString(R.string.cardinal_e)
            3 -> getString(R.string.cardinal_se)
            4 -> getString(R.string.cardinal_s)
            5 -> getString(R.string.cardinal_sw)
            6 -> getString(R.string.cardinal_w)
            7 -> getString(R.string.cardinal_nw)
            else -> getString(R.string.cardinal_n)
        }
    }

    private fun updateHudMediaInfo() {
        val track = MediaControlDispatcher.getActiveTrackInfo(this)
        val selectedPlayer = MediaControlDispatcher.getSelectedName(this)

        if (track != null && track.title.isNotBlank()) {
            binding.tvHudSongTitle.text = track.title
            binding.tvHudSongArtist.text = if (track.artist.isNotBlank()) {
                "${track.artist} · ${track.appName}"
            } else {
                track.appName
            }
            binding.btnHudPlayPause.setIconResource(
                if (track.isPlaying) R.drawable.ic_pause else R.drawable.ic_play
            )
            binding.btnHudPlayPause.contentDescription = getString(
                if (track.isPlaying) R.string.btn_pause else R.string.btn_play
            )
        } else {
            binding.tvHudSongTitle.text = getString(R.string.hud_music_ready)
            binding.tvHudSongArtist.text = getString(R.string.hud_player_format, selectedPlayer)
            binding.btnHudPlayPause.setIconResource(R.drawable.ic_play)
            binding.btnHudPlayPause.contentDescription = getString(R.string.btn_play)
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

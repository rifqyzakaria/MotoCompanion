package com.motocompanion.ui

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.motocompanion.App
import com.motocompanion.BuildConfig
import com.motocompanion.databinding.ActivityMainBinding
import com.motocompanion.service.GpsTrackingService
import com.motocompanion.service.MediaSessionService
import com.motocompanion.service.NotificationAnnouncerService
import com.motocompanion.update.AppUpdateManager
import com.motocompanion.util.MediaControlDispatcher
import com.motocompanion.util.RoadSyncBridge

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val locationPermissionReqCode = 101

    private val trackUpdateHandler = Handler(Looper.getMainLooper())
    private val trackUpdateRunnable = object : Runnable {
        override fun run() {
            updateTrackInfoUi()
            trackUpdateHandler.postDelayed(this, 2000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupListeners()
        loadPreferences()

        // Periksa pembaruan di latar belakang saat aplikasi dibuka
        AppUpdateManager.checkForUpdates(this, showUpToDateToast = false)
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatuses()
        updateActivePlayerName()
        updateRoadSyncUi()
        updateGithubRepoUi()
        trackUpdateHandler.post(trackUpdateRunnable)
    }

    override fun onPause() {
        super.onPause()
        trackUpdateHandler.removeCallbacks(trackUpdateRunnable)
    }

    private fun setupListeners() {
        // Integrasi Pendamping Honda RoadSync (PCX)
        binding.btnLaunchRoadSync.setOnClickListener {
            val launched = RoadSyncBridge.launchRoadSync(this)
            if (!launched) {
                AlertDialog.Builder(this)
                    .setTitle("Honda RoadSync Belum Terpasang")
                    .setMessage("Aplikasi resmi Honda RoadSync tidak ditemukan di HP ini. Unduh dari Google Play Store?")
                    .setPositiveButton("Buka Play Store") { _, _ ->
                        try {
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${RoadSyncBridge.PKG_HONDA_ROADSYNC}")))
                        } catch (_: Exception) {
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=${RoadSyncBridge.PKG_HONDA_ROADSYNC}")))
                        }
                    }
                    .setNegativeButton("Batal", null)
                    .show()
            }
        }

        binding.btnRoadSyncGuide.setOnClickListener {
            showRoadSyncGuideDialog()
        }

        binding.btnToggleCompanion.setOnClickListener {
            toggleCompanionMode()
        }

        binding.switchAutoStartCompanion.setOnCheckedChangeListener { _, isChecked ->
            RoadSyncBridge.setAutoStartEnabled(this, isChecked)
            Toast.makeText(
                this,
                if (isChecked) "Auto-start aktif saat Bluetooth PCX terhubung" else "Auto-start dinonaktifkan",
                Toast.LENGTH_SHORT
            ).show()
        }

        // Kontrol Cepat Pemutar Media Terpilih (Dashboard Utama)
        binding.btnMainPrev.setOnClickListener {
            MediaControlDispatcher.dispatchMediaAction(this, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            val name = MediaControlDispatcher.getSelectedName(this)
            Toast.makeText(this, "⏮ Lagu Sebelumnya dikirim ke $name", Toast.LENGTH_SHORT).show()
            trackUpdateHandler.postDelayed({ updateTrackInfoUi() }, 400)
        }

        binding.btnMainPlayPause.setOnClickListener {
            MediaControlDispatcher.dispatchMediaAction(this, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            val name = MediaControlDispatcher.getSelectedName(this)
            Toast.makeText(this, "⏯ Play/Pause dikirim ke $name", Toast.LENGTH_SHORT).show()
            trackUpdateHandler.postDelayed({ updateTrackInfoUi() }, 400)
        }

        binding.btnMainNext.setOnClickListener {
            MediaControlDispatcher.dispatchMediaAction(this, KeyEvent.KEYCODE_MEDIA_NEXT)
            val name = MediaControlDispatcher.getSelectedName(this)
            Toast.makeText(this, "Next ⏭ dikirim ke $name", Toast.LENGTH_SHORT).show()
            trackUpdateHandler.postDelayed({ updateTrackInfoUi() }, 400)
        }

        // Pengaturan & Pemilihan Pemutar Media
        binding.btnChangePlayer.setOnClickListener {
            startActivity(Intent(this, PlayerSelectorActivity::class.java))
        }

        binding.btnLaunchActivePlayer.setOnClickListener {
            MediaControlDispatcher.launchSelectedPlayer(this)
        }

        binding.btnOpenBuiltinMusic.setOnClickListener {
            startActivity(Intent(this, MusicActivity::class.java))
        }

        // Pembaruan Aplikasi GitHub
        binding.btnCheckUpdate.setOnClickListener {
            Toast.makeText(this, "Memeriksa rilis terbaru di GitHub...", Toast.LENGTH_SHORT).show()
            AppUpdateManager.checkForUpdates(this, showUpToDateToast = true)
        }

        binding.btnSetRepo.setOnClickListener {
            showSetRepoDialog()
        }

        // Izin Notifikasi
        binding.btnGrantNotification.setOnClickListener {
            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            startActivity(intent)
        }

        // Izin Baterai
        binding.btnGrantBattery.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            }
        }

        // Izin Lokasi
        binding.btnGrantLocation.setOnClickListener {
            requestLocationPermission()
        }

        // Preferences Switches
        val prefs = getSharedPreferences(NotificationAnnouncerService.PREFS_NAME, Context.MODE_PRIVATE)

        binding.switchWaCalls.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(NotificationAnnouncerService.KEY_ANNOUNCE_CALLS, isChecked).apply()
        }

        binding.switchWaMessages.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(NotificationAnnouncerService.KEY_ANNOUNCE_MESSAGES, isChecked).apply()
        }

        binding.switchGpsStabilizer.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                startGpsService()
            } else {
                stopService(Intent(this, GpsTrackingService::class.java))
            }
        }

        // Tes Audio & Simulasi
        binding.btnTestVoice.setOnClickListener {
            App.instance.ttsManager.speak(
                "Tes audio intercom berhasil. Sistem Moto Companion siap mendampingi perjalanan Anda.",
                isPriority = true
            )
            Toast.makeText(this, "Mengirim suara tes ke intercom/headset...", Toast.LENGTH_SHORT).show()
        }

        binding.btnTestCall.setOnClickListener {
            App.instance.ttsManager.speak(
                "Telepon WhatsApp masuk dari Budi Santoso",
                isPriority = true
            )
            Toast.makeText(this, "Mensimulasikan panggilan WhatsApp masuk...", Toast.LENGTH_SHORT).show()
        }

        // Buka Layar Kendara
        binding.btnOpenHud.setOnClickListener {
            if (hasLocationPermission()) {
                startGpsService()
                startActivity(Intent(this, HudActivity::class.java))
            } else {
                requestLocationPermission()
            }
        }
    }

    private fun showSetRepoDialog() {
        val currentRepo = AppUpdateManager.getSavedRepo(this)
        val input = EditText(this).apply {
            setText(currentRepo)
            hint = "username/repository"
        }

        AlertDialog.Builder(this)
            .setTitle("Atur Repositori GitHub")
            .setMessage("Masukkan format 'username/nama-repo' tempat Anda merilis file APK pembaruan:")
            .setView(input)
            .setPositiveButton("Simpan") { _, _ ->
                val newRepo = input.text.toString().trim()
                if (newRepo.isNotBlank()) {
                    AppUpdateManager.setSavedRepo(this, newRepo)
                    updateGithubRepoUi()
                    Toast.makeText(this, "Repositori disimpan: $newRepo", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun updateGithubRepoUi() {
        val version = BuildConfig.VERSION_NAME
        binding.tvCurrentVersion.text = "Versi Saat Ini: v$version"
        binding.tvGithubRepo.text = "Repo: ${AppUpdateManager.getSavedRepo(this)}"
    }

    private fun showRoadSyncGuideDialog() {
        AlertDialog.Builder(this)
            .setTitle("🏍️ Hubungkan ke Honda RoadSync (PCX)")
            .setMessage(
                "Langkah menghubungkan agar tombol stang PCX mengontrol musik Anda:\n\n" +
                "1. Pastikan Bluetooth ponsel terhubung ke motor Honda PCX & Intercom helm.\n\n" +
                "2. Buka aplikasi resmi Honda RoadSync.\n\n" +
                "3. Buka Pengaturan (Settings) ➔ Sumber Musik (Music Source).\n\n" +
                "4. Pilih 'MotoCompanion' sebagai sumber musik!\n\n" +
                "5. Di MotoCompanion, pilih pemutar musik yang Anda inginkan (Spotify, YouTube Music, VLC, dll.).\n\n" +
                "6. Selesai! Saat berkendara, tombol stang motor Honda PCX akan langsung mengontrol lagu dan judul lagu tampil di speedometer!"
            )
            .setPositiveButton("Buka RoadSync Sekarang") { _, _ ->
                RoadSyncBridge.launchRoadSync(this)
            }
            .setNegativeButton("Tutup", null)
            .show()
    }

    private fun toggleCompanionMode() {
        val currentlyActive = RoadSyncBridge.isCompanionActive(this)
        if (!currentlyActive) {
            RoadSyncBridge.setCompanionActive(this, true)
            val serviceIntent = Intent(this, MediaSessionService::class.java).apply {
                action = MediaSessionService.ACTION_TOGGLE
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ContextCompat.startForegroundService(this, serviceIntent)
                } else {
                    startService(serviceIntent)
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Gagal start MediaSessionService: ${e.message}")
            }

            if (hasLocationPermission()) {
                startGpsService()
            }

            Toast.makeText(this, "🏍️ Mode Pendamping Honda PCX Aktif!", Toast.LENGTH_SHORT).show()
        } else {
            RoadSyncBridge.setCompanionActive(this, false)
            Toast.makeText(this, "Mode Pendamping Dinonaktifkan", Toast.LENGTH_SHORT).show()
        }
        updateRoadSyncUi()
    }

    private fun updateRoadSyncUi() {
        val isInstalled = RoadSyncBridge.isRoadSyncInstalled(this)
        val isCompanionActive = RoadSyncBridge.isCompanionActive(this)
        val connectedDevice = RoadSyncBridge.getConnectedDeviceName(this)

        if (isCompanionActive) {
            binding.tvRoadSyncStatus.text = "Status: Aktif Mendampingi Honda PCX 🟢"
            binding.tvRoadSyncStatus.setTextColor(ContextCompat.getColor(this, com.motocompanion.R.color.accent_green))
            binding.btnToggleCompanion.text = "Matikan"
            binding.btnToggleCompanion.setTextColor(ContextCompat.getColor(this, com.motocompanion.R.color.accent_red))
            (binding.btnToggleCompanion as? com.google.android.material.button.MaterialButton)?.strokeColor = ContextCompat.getColorStateList(this, com.motocompanion.R.color.accent_red)
        } else if (isInstalled) {
            binding.tvRoadSyncStatus.text = "Honda RoadSync: Siap Dampingi"
            binding.tvRoadSyncStatus.setTextColor(ContextCompat.getColor(this, com.motocompanion.R.color.text_primary))
            binding.btnToggleCompanion.text = "Aktifkan"
            binding.btnToggleCompanion.setTextColor(ContextCompat.getColor(this, com.motocompanion.R.color.accent_green))
            (binding.btnToggleCompanion as? com.google.android.material.button.MaterialButton)?.strokeColor = ContextCompat.getColorStateList(this, com.motocompanion.R.color.accent_green)
        } else {
            binding.tvRoadSyncStatus.text = "Honda RoadSync: Belum Terpasang"
            binding.tvRoadSyncStatus.setTextColor(ContextCompat.getColor(this, com.motocompanion.R.color.text_secondary))
            binding.btnToggleCompanion.text = "Aktifkan"
        }

        if (connectedDevice != null) {
            binding.tvBluetoothDeviceName.text = "Bluetooth Motor/Helm: Terhubung ($connectedDevice)"
            binding.tvBluetoothDeviceName.setTextColor(ContextCompat.getColor(this, com.motocompanion.R.color.accent_green))
        } else {
            binding.tvBluetoothDeviceName.text = "Bluetooth Motor/Helm: Belum Terhubung"
            binding.tvBluetoothDeviceName.setTextColor(ContextCompat.getColor(this, com.motocompanion.R.color.accent_amber))
        }

        binding.switchAutoStartCompanion.isChecked = RoadSyncBridge.isAutoStartEnabled(this)
    }

    private fun updateActivePlayerName() {
        val selectedName = MediaControlDispatcher.getSelectedName(this)
        binding.tvActivePlayerName.text = selectedName
        updateTrackInfoUi()
    }

    private fun updateTrackInfoUi() {
        val track = MediaControlDispatcher.getActiveTrackInfo(this)
        if (track != null && track.title.isNotBlank()) {
            binding.tvActiveTrackInfo.text = "🎵 ${track.title} • ${track.artist}"
            if (track.isPlaying) {
                binding.tvActiveTrackState.text = "Memutar"
                binding.tvActiveTrackState.setTextColor(ContextCompat.getColor(this, com.motocompanion.R.color.accent_green))
                binding.btnMainPlayPause.text = "⏸ Jeda"
            } else {
                binding.tvActiveTrackState.text = "Jeda / Siap"
                binding.tvActiveTrackState.setTextColor(ContextCompat.getColor(this, com.motocompanion.R.color.text_secondary))
                binding.btnMainPlayPause.text = "▶ Putar"
            }
        }
    }

    private fun loadPreferences() {
        val prefs = getSharedPreferences(NotificationAnnouncerService.PREFS_NAME, Context.MODE_PRIVATE)
        binding.switchWaCalls.isChecked = prefs.getBoolean(NotificationAnnouncerService.KEY_ANNOUNCE_CALLS, true)
        binding.switchWaMessages.isChecked = prefs.getBoolean(NotificationAnnouncerService.KEY_ANNOUNCE_MESSAGES, false)
    }

    private fun updatePermissionStatuses() {
        // 1. Cek Notification Listener
        val isNotificationGranted = isNotificationServiceEnabled()
        if (isNotificationGranted) {
            binding.tvNotificationStatus.text = "Aktif (Mendeteksi WhatsApp)"
            binding.tvNotificationStatus.setTextColor(ContextCompat.getColor(this, com.motocompanion.R.color.accent_green))
            binding.btnGrantNotification.text = "Sudah Aktif"
            binding.btnGrantNotification.isEnabled = false
        } else {
            binding.tvNotificationStatus.text = "Perlu diaktifkan agar bisa membaca WA"
            binding.tvNotificationStatus.setTextColor(ContextCompat.getColor(this, com.motocompanion.R.color.accent_amber))
            binding.btnGrantNotification.text = "Aktifkan"
            binding.btnGrantNotification.isEnabled = true
        }

        // 2. Cek Battery Optimization
        val isBatteryIgnoring = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.isIgnoringBatteryOptimizations(packageName)
        } else true

        if (isBatteryIgnoring) {
            binding.tvBatteryStatus.text = "Tidak Dibatasi (Latar belakang aman)"
            binding.tvBatteryStatus.setTextColor(ContextCompat.getColor(this, com.motocompanion.R.color.accent_green))
            binding.btnGrantBattery.text = "Sudah Aktif"
            binding.btnGrantBattery.isEnabled = false
        } else {
            binding.tvBatteryStatus.text = "Pilih 'Tidak Dibatasi' agar GPS tidak tertidur"
            binding.tvBatteryStatus.setTextColor(ContextCompat.getColor(this, com.motocompanion.R.color.accent_amber))
            binding.btnGrantBattery.text = "Aktifkan"
            binding.btnGrantBattery.isEnabled = true
        }

        // 3. Cek Location
        if (hasLocationPermission()) {
            binding.tvLocationStatus.text = "Izin GPS Aktif"
            binding.tvLocationStatus.setTextColor(ContextCompat.getColor(this, com.motocompanion.R.color.accent_green))
            binding.btnGrantLocation.text = "Sudah Aktif"
            binding.btnGrantLocation.isEnabled = false
            startGpsService()
        } else {
            binding.tvLocationStatus.text = "Perlu izin GPS untuk speedometer & arah"
            binding.tvLocationStatus.setTextColor(ContextCompat.getColor(this, com.motocompanion.R.color.accent_amber))
            binding.btnGrantLocation.text = "Izinkan"
            binding.btnGrantLocation.isEnabled = true
        }
    }

    private fun isNotificationServiceEnabled(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return flat?.contains(packageName) == true
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestLocationPermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ),
            locationPermissionReqCode
        )
    }

    private fun startGpsService() {
        if (hasLocationPermission()) {
            val serviceIntent = Intent(this, GpsTrackingService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        }
    }
}

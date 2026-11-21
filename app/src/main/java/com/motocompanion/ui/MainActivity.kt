package com.motocompanion.ui

import android.Manifest
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.motocompanion.App
import com.motocompanion.BuildConfig
import com.motocompanion.R
import com.motocompanion.databinding.ActivityMainBinding
import com.motocompanion.service.GpsTrackingService
import com.motocompanion.service.NotificationAnnouncerService
import com.motocompanion.update.AppUpdateManager
import com.motocompanion.util.LocaleThemeManager
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

    private val roadSyncReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            updateRoadSyncUi()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupListeners()
        loadPreferences()
        updateThemeAndLanguageUi()
        updateMusicPlayerSwitchUi(RoadSyncBridge.isMusicPlayerEnabled(this))

        // Periksa pembaruan di latar belakang saat aplikasi dibuka
        AppUpdateManager.checkForUpdates(this, showUpToDateToast = false)

        // Otomatis aktifkan hanya jika motor Honda / helm sedang terhubung
        val isBikeConnected = RoadSyncBridge.getConnectedDeviceName(this) != null
        if (isBikeConnected) {
            RoadSyncBridge.startAllServices(this, "MainActivity OnCreate with Connected Bike")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        val isBikeConnected = RoadSyncBridge.getConnectedDeviceName(this) != null
        if (!isBikeConnected && !RoadSyncBridge.isRoadSyncActive) {
            RoadSyncBridge.stopAllServices(this, "MainActivity Closed with No Bike")
        }
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatuses()
        updateActivePlayerName()
        updateRoadSyncUi()
        updateGithubRepoUi()
        updateThemeAndLanguageUi()
        updateMusicPlayerSwitchUi(RoadSyncBridge.isMusicPlayerEnabled(this))
        trackUpdateHandler.post(trackUpdateRunnable)

        val filter = IntentFilter().apply {
            addAction(RoadSyncBridge.ACTION_ROADSYNC_STATE_CHANGED)
            addAction(RoadSyncBridge.ACTION_CAR_CONNECTION_UPDATED)
            addAction(android.app.UiModeManager.ACTION_ENTER_CAR_MODE)
            addAction(android.app.UiModeManager.ACTION_EXIT_CAR_MODE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(roadSyncReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(roadSyncReceiver, filter)
        }
    }

    override fun onPause() {
        super.onPause()
        trackUpdateHandler.removeCallbacks(trackUpdateRunnable)
        try {
            unregisterReceiver(roadSyncReceiver)
        } catch (_: Exception) {}
    }

    private fun setupListeners() {
        // Integrasi Pendamping Honda RoadSync
        binding.btnLaunchRoadSync.setOnClickListener {
            val launched = RoadSyncBridge.launchRoadSync(this)
            if (!launched) {
                AlertDialog.Builder(this)
                    .setTitle(R.string.roadsync_not_installed_title)
                    .setMessage(R.string.roadsync_not_installed_message)
                    .setPositiveButton(R.string.btn_open_playstore) { _, _ ->
                        try {
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${RoadSyncBridge.PKG_HONDA_ROADSYNC}")))
                        } catch (_: Exception) {
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=${RoadSyncBridge.PKG_HONDA_ROADSYNC}")))
                        }
                    }
                    .setNegativeButton(R.string.btn_cancel, null)
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
                if (isChecked) getString(R.string.toast_auto_start_on) else getString(R.string.toast_auto_start_off),
                Toast.LENGTH_SHORT
            ).show()
        }

        // Switch Pemutar Musik H-Sync untuk Honda RoadSync
        binding.switchHsyncMusic.setOnCheckedChangeListener { _, isChecked ->
            RoadSyncBridge.setMusicPlayerEnabled(this, isChecked)
            updateMusicPlayerSwitchUi(isChecked)
            Toast.makeText(
                this,
                if (isChecked) getString(R.string.toast_hsync_music_on) else getString(R.string.toast_hsync_music_off),
                Toast.LENGTH_LONG
            ).show()
        }

        // Kontrol Cepat Pemutar Media Terpilih (Dashboard Utama)
        binding.btnMainPrev.setOnClickListener {
            MediaControlDispatcher.dispatchMediaAction(this, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            val name = MediaControlDispatcher.getSelectedName(this)
            Toast.makeText(this, getString(R.string.toast_cmd_sent_prev, name), Toast.LENGTH_SHORT).show()
            trackUpdateHandler.postDelayed({ updateTrackInfoUi() }, 400)
        }

        binding.btnMainPlayPause.setOnClickListener {
            MediaControlDispatcher.dispatchMediaAction(this, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            val name = MediaControlDispatcher.getSelectedName(this)
            Toast.makeText(this, getString(R.string.toast_cmd_sent_play_pause, name), Toast.LENGTH_SHORT).show()
            trackUpdateHandler.postDelayed({ updateTrackInfoUi() }, 400)
        }

        binding.btnMainNext.setOnClickListener {
            MediaControlDispatcher.dispatchMediaAction(this, KeyEvent.KEYCODE_MEDIA_NEXT)
            val name = MediaControlDispatcher.getSelectedName(this)
            Toast.makeText(this, getString(R.string.toast_cmd_sent_next, name), Toast.LENGTH_SHORT).show()
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

        // Pengaturan Tampilan & Bahasa
        binding.btnSelectLanguage.setOnClickListener {
            showLanguageDialog()
        }

        binding.btnSelectTheme.setOnClickListener {
            showThemeDialog()
        }

        // Pembaruan Aplikasi GitHub Resmi (@mrz92)
        binding.btnCheckUpdate.setOnClickListener {
            Toast.makeText(this, getString(R.string.toast_checking_update), Toast.LENGTH_SHORT).show()
            AppUpdateManager.checkForUpdates(this, showUpToDateToast = true)
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
            val voiceText = getString(R.string.tts_test_intercom)
            App.instance.ttsManager.speak(voiceText, isPriority = true)
            Toast.makeText(this, getString(R.string.toast_testing_voice), Toast.LENGTH_SHORT).show()
        }

        binding.btnTestCall.setOnClickListener {
            val callText = getString(R.string.tts_test_call, "Budi Santoso")
            App.instance.ttsManager.speak(callText, isPriority = true)
            Toast.makeText(this, getString(R.string.toast_testing_call), Toast.LENGTH_SHORT).show()
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

    private fun updateMusicPlayerSwitchUi(enabled: Boolean) {
        binding.switchHsyncMusic.isChecked = enabled
        if (enabled) {
            binding.tvHsyncMusicDesc.text = getString(R.string.switch_hsync_music_desc_on)
            binding.layoutMusicPlayerControls.alpha = 1.0f
            binding.btnChangePlayer.isEnabled = true
            binding.btnMainPrev.isEnabled = true
            binding.btnMainPlayPause.isEnabled = true
            binding.btnMainNext.isEnabled = true
            binding.btnLaunchActivePlayer.isEnabled = true
            binding.btnOpenBuiltinMusic.isEnabled = true
        } else {
            binding.tvHsyncMusicDesc.text = getString(R.string.switch_hsync_music_desc_off)
            binding.layoutMusicPlayerControls.alpha = 0.45f
            binding.btnChangePlayer.isEnabled = false
            binding.btnMainPrev.isEnabled = false
            binding.btnMainPlayPause.isEnabled = false
            binding.btnMainNext.isEnabled = false
            binding.btnLaunchActivePlayer.isEnabled = false
            binding.btnOpenBuiltinMusic.isEnabled = false
        }
    }

    private fun updateThemeAndLanguageUi() {
        val currentTheme = LocaleThemeManager.getTheme(this)
        binding.tvCurrentTheme.text = LocaleThemeManager.getThemeDisplayName(this, currentTheme)

        val currentLang = LocaleThemeManager.getLanguage(this)
        binding.tvCurrentLanguage.text = LocaleThemeManager.getLanguageDisplayName(this, currentLang)
    }

    private fun showLanguageDialog() {
        val languages = arrayOf(
            getString(R.string.lang_indonesian),
            getString(R.string.lang_english),
            getString(R.string.lang_system)
        )
        val codes = arrayOf(
            LocaleThemeManager.LANG_IN,
            LocaleThemeManager.LANG_EN,
            LocaleThemeManager.LANG_SYSTEM
        )
        val currentCode = LocaleThemeManager.getLanguage(this)
        val currentIndex = when (currentCode) {
            LocaleThemeManager.LANG_IN -> 0
            LocaleThemeManager.LANG_EN -> 1
            else -> 2
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_select_language)
            .setSingleChoiceItems(languages, currentIndex) { dialog, which ->
                val selectedCode = codes[which]
                dialog.dismiss()
                if (selectedCode != currentCode) {
                    LocaleThemeManager.setLanguage(this, selectedCode)
                    updateThemeAndLanguageUi()
                }
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun showThemeDialog() {
        val themes = arrayOf(
            getString(R.string.theme_system),
            getString(R.string.theme_dark),
            getString(R.string.theme_light)
        )
        val codes = arrayOf(
            LocaleThemeManager.THEME_SYSTEM,
            LocaleThemeManager.THEME_DARK,
            LocaleThemeManager.THEME_LIGHT
        )
        val currentCode = LocaleThemeManager.getTheme(this)
        val currentIndex = when (currentCode) {
            LocaleThemeManager.THEME_DARK -> 1
            LocaleThemeManager.THEME_LIGHT -> 2
            else -> 0
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_select_theme)
            .setSingleChoiceItems(themes, currentIndex) { dialog, which ->
                val selectedCode = codes[which]
                dialog.dismiss()
                if (selectedCode != currentCode) {
                    LocaleThemeManager.setTheme(this, selectedCode)
                    updateThemeAndLanguageUi()
                }
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun updateGithubRepoUi() {
        val version = BuildConfig.VERSION_NAME
        binding.tvCurrentVersion.text = getString(R.string.current_version_format, version)
        binding.tvGithubRepo.text = getString(R.string.official_source_desc)
    }

    private fun showRoadSyncGuideDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.guide_title)
            .setMessage(R.string.guide_message)
            .setPositiveButton(R.string.guide_btn_open) { _, _ ->
                RoadSyncBridge.launchRoadSync(this)
            }
            .setNegativeButton(R.string.guide_btn_close, null)
            .show()
    }

    private fun toggleCompanionMode() {
        val currentlyActive = RoadSyncBridge.isCompanionActive(this)
        if (!currentlyActive) {
            RoadSyncBridge.startAllServices(this, "Manual Toggle")
            Toast.makeText(this, getString(R.string.toast_companion_active), Toast.LENGTH_SHORT).show()
        } else {
            RoadSyncBridge.stopAllServices(this, "Manual Toggle")
            Toast.makeText(this, getString(R.string.toast_companion_stopped), Toast.LENGTH_SHORT).show()
        }
        updateRoadSyncUi()
    }

    private fun updateRoadSyncUi() {
        val isAndroidAuto = RoadSyncBridge.isAndroidAutoConnected(this)
        val isInstalled = RoadSyncBridge.isRoadSyncInstalled(this)
        val isCompanionActive = RoadSyncBridge.isCompanionActive(this) || RoadSyncBridge.isRoadSyncActive
        val connectedDevice = RoadSyncBridge.getConnectedDeviceName(this)

        if (isAndroidAuto) {
            binding.tvRoadSyncStatus.text = getString(R.string.status_android_auto_active)
            binding.tvRoadSyncStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_amber))
            binding.btnToggleCompanion.text = getString(R.string.btn_turn_on)
            binding.btnToggleCompanion.isEnabled = false
            binding.tvHeaderStatusBadge.text = "🚗 MOBIL"
            binding.tvHeaderStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.accent_amber))
            binding.tvHeaderStatusBadge.setBackgroundResource(R.drawable.bg_pill_pending)
        } else if (isCompanionActive) {
            binding.btnToggleCompanion.isEnabled = true
            binding.tvRoadSyncStatus.text = getString(R.string.roadsync_status_active)
            binding.tvRoadSyncStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_green))
            binding.btnToggleCompanion.text = getString(R.string.btn_turn_off)
            binding.btnToggleCompanion.setTextColor(ContextCompat.getColor(this, R.color.accent_red))
            (binding.btnToggleCompanion as? com.google.android.material.button.MaterialButton)?.strokeColor = ContextCompat.getColorStateList(this, R.color.accent_red)
            binding.tvHeaderStatusBadge.text = getString(R.string.status_active)
            binding.tvHeaderStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.accent_green))
            binding.tvHeaderStatusBadge.setBackgroundResource(R.drawable.bg_pill_active)
        } else if (isInstalled) {
            binding.btnToggleCompanion.isEnabled = true
            binding.tvRoadSyncStatus.text = getString(R.string.roadsync_status_ready)
            binding.tvRoadSyncStatus.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
            binding.btnToggleCompanion.text = getString(R.string.btn_turn_on)
            binding.btnToggleCompanion.setTextColor(ContextCompat.getColor(this, R.color.accent_green))
            (binding.btnToggleCompanion as? com.google.android.material.button.MaterialButton)?.strokeColor = ContextCompat.getColorStateList(this, R.color.accent_green)
            binding.tvHeaderStatusBadge.text = getString(R.string.status_standby)
            binding.tvHeaderStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            binding.tvHeaderStatusBadge.setBackgroundResource(R.drawable.bg_pill)
        } else {
            binding.btnToggleCompanion.isEnabled = true
            binding.tvRoadSyncStatus.text = getString(R.string.roadsync_status_not_installed)
            binding.tvRoadSyncStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            binding.btnToggleCompanion.text = getString(R.string.btn_turn_on)
            binding.tvHeaderStatusBadge.text = getString(R.string.status_offline)
            binding.tvHeaderStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.text_muted))
            binding.tvHeaderStatusBadge.setBackgroundResource(R.drawable.bg_pill)
        }

        if (connectedDevice != null) {
            binding.tvBluetoothDeviceName.text = getString(R.string.bt_connected, connectedDevice)
            binding.tvBluetoothDeviceName.setTextColor(ContextCompat.getColor(this, R.color.accent_green))
        } else {
            binding.tvBluetoothDeviceName.text = getString(R.string.bt_waiting)
            binding.tvBluetoothDeviceName.setTextColor(ContextCompat.getColor(this, R.color.accent_amber))
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
                binding.tvActiveTrackState.text = getString(R.string.state_playing)
                binding.tvActiveTrackState.setTextColor(ContextCompat.getColor(this, R.color.accent_green))
                binding.btnMainPlayPause.text = getString(R.string.btn_pause)
            } else {
                binding.tvActiveTrackState.text = getString(R.string.state_paused)
                binding.tvActiveTrackState.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
                binding.btnMainPlayPause.text = getString(R.string.btn_play)
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
            binding.tvNotificationStatus.text = getString(R.string.status_granted_notification)
            binding.tvNotificationStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_green))
            binding.btnGrantNotification.text = getString(R.string.btn_granted)
            binding.btnGrantNotification.isEnabled = false
        } else {
            binding.tvNotificationStatus.text = getString(R.string.status_grant_notification_hint)
            binding.tvNotificationStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_amber))
            binding.btnGrantNotification.text = getString(R.string.btn_configure)
            binding.btnGrantNotification.isEnabled = true
        }

        // 2. Cek Battery Optimization
        val isBatteryIgnoring = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.isIgnoringBatteryOptimizations(packageName)
        } else true

        if (isBatteryIgnoring) {
            binding.tvBatteryStatus.text = getString(R.string.status_granted_battery)
            binding.tvBatteryStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_green))
            binding.btnGrantBattery.text = getString(R.string.btn_granted)
            binding.btnGrantBattery.isEnabled = false
        } else {
            binding.tvBatteryStatus.text = getString(R.string.status_grant_battery_hint)
            binding.tvBatteryStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_amber))
            binding.btnGrantBattery.text = getString(R.string.btn_configure)
            binding.btnGrantBattery.isEnabled = true
        }

        // 3. Cek Location
        if (hasLocationPermission()) {
            binding.tvLocationStatus.text = getString(R.string.status_granted_location)
            binding.tvLocationStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_green))
            binding.btnGrantLocation.text = getString(R.string.btn_granted)
            binding.btnGrantLocation.isEnabled = false
            startGpsService()
        } else {
            binding.tvLocationStatus.text = getString(R.string.status_grant_location_hint)
            binding.tvLocationStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_amber))
            binding.btnGrantLocation.text = getString(R.string.btn_configure)
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

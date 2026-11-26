package com.motocompanion.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat
import com.motocompanion.App
import com.motocompanion.BuildConfig
import com.motocompanion.R
import com.motocompanion.databinding.ActivityMainBinding
import com.motocompanion.service.NotificationAnnouncerService
import com.motocompanion.update.AppUpdateManager
import com.motocompanion.util.ContactNameResolver
import com.motocompanion.util.LocaleThemeManager
import com.motocompanion.util.MediaControlDispatcher
import com.motocompanion.util.RoadSyncBridge
import kotlin.math.abs

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val runtimePermissionReqCode = 101
    private val backgroundLocationReqCode = 102
    private val easeOutInterpolator = DecelerateInterpolator(1.6f)

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
        setupScrollCraftTimeline()
        setupTactileMicroInteractions()
        loadPreferences()
        updateThemeAndLanguageUi()
        updateMusicPlayerSwitchUi(RoadSyncBridge.isMusicPlayerEnabled(this))

        requestEssentialPermissionsIfNeeded()

        AppUpdateManager.checkForUpdates(this, showUpToDateToast = false)

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
        updateWaMessageSubtitle()
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

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        updatePermissionStatuses()
        if (RoadSyncBridge.isRoadSyncActive || RoadSyncBridge.getConnectedDeviceName(this) != null) {
            if (hasLocationPermission() && RoadSyncBridge.isGpsRealtimeEnabled(this)) {
                RoadSyncBridge.startGpsService(this)
            }
        }
    }

    /**
     * Scroll-Craft Signature Move:
     * Treats the scroll container as a 60fps timeline using strictly GPU-composited
     * transform (translationY, scaleX, scaleY) and opacity (alpha) properties,
     * with full accessibility fallback when system animations / reduced motion are active.
     */
    private fun setupScrollCraftTimeline() {
        val headerFadeRangePx = dpToPx(48f)
        val heroDepthRangePx = dpToPx(240f)

        binding.scrollDashboard.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            val headerProgress = (scrollY / headerFadeRangePx).coerceIn(0f, 1f)
            binding.headerDivider.alpha = headerProgress

            if (isReducedMotionEnabled()) {
                binding.headerBrandGroup.scaleX = 1f
                binding.headerBrandGroup.scaleY = 1f
                binding.cardHeroTelemetry.translationY = 0f
                binding.cardHeroTelemetry.scaleX = 1f
                binding.cardHeroTelemetry.scaleY = 1f
                binding.cardHeroTelemetry.alpha = 1f
                binding.cardPeakCockpit.scaleX = 1f
                binding.cardPeakCockpit.scaleY = 1f
                return@setOnScrollChangeListener
            }

            // Condense header brand subtly on scroll
            val headerScale = 1f - (0.03f * headerProgress)
            binding.headerBrandGroup.scaleX = headerScale
            binding.headerBrandGroup.scaleY = headerScale

            // Asymmetric Hero subtle depth recede (transform + opacity only)
            val heroProgress = (scrollY / heroDepthRangePx).coerceIn(0f, 1f)
            binding.cardHeroTelemetry.translationY = scrollY * 0.16f
            val heroScale = 1f - (0.024f * heroProgress)
            binding.cardHeroTelemetry.scaleX = heroScale
            binding.cardHeroTelemetry.scaleY = heroScale
            binding.cardHeroTelemetry.alpha = 1f - (0.28f * heroProgress)

            // Single Engineered Peak focal presence as it enters viewport prime zone
            val viewportHeight = binding.scrollDashboard.height
            if (viewportHeight > 0 && binding.cardPeakCockpit.height > 0) {
                val peakCenterY = (binding.cardPeakCockpit.top + binding.cardPeakCockpit.bottom) * 0.5f - scrollY
                val viewportCenterY = viewportHeight * 0.5f
                val normalizedDistance = (abs(peakCenterY - viewportCenterY) / (viewportHeight * 0.65f)).coerceIn(0f, 1f)
                val peakFocusScale = 1f - (0.016f * normalizedDistance)
                binding.cardPeakCockpit.scaleX = peakFocusScale
                binding.cardPeakCockpit.scaleY = peakFocusScale
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTactileMicroInteractions() {
        val interactiveViews = listOf<View>(
            binding.btnToggleCompanion,
            binding.btnOpenHud,
            binding.btnMainPrev,
            binding.btnMainPlayPause,
            binding.btnMainNext,
            binding.cardLanguageTile,
            binding.cardThemeTile,
            binding.btnCheckUpdate
        )

        interactiveViews.forEach { target ->
            target.setOnTouchListener { v, event ->
                if (!isReducedMotionEnabled() && v.isEnabled) {
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            v.animate()
                                .scaleX(0.975f)
                                .scaleY(0.975f)
                                .setDuration(120L)
                                .setInterpolator(easeOutInterpolator)
                                .start()
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            v.animate()
                                .scaleX(1f)
                                .scaleY(1f)
                                .setDuration(160L)
                                .setInterpolator(easeOutInterpolator)
                                .start()
                        }
                    }
                }
                false
            }
        }
    }

    private fun isReducedMotionEnabled(): Boolean {
        val scale = try {
            Settings.Global.getFloat(
                contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1.0f
            )
        } catch (_: Exception) {
            1.0f
        }
        return scale == 0f
    }

    private fun dpToPx(dp: Float): Float {
        return dp * resources.displayMetrics.density
    }

    private fun setupListeners() {
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

        binding.switchHsyncMusic.setOnCheckedChangeListener { _, isChecked ->
            RoadSyncBridge.setMusicPlayerEnabled(this, isChecked)
            updateMusicPlayerSwitchUi(isChecked)
            Toast.makeText(
                this,
                if (isChecked) getString(R.string.toast_hsync_music_on) else getString(R.string.toast_hsync_music_off),
                Toast.LENGTH_LONG
            ).show()
        }

        binding.switchAutoVolume20.setOnCheckedChangeListener { _, isChecked ->
            RoadSyncBridge.setAutoVolume20Enabled(this, isChecked)
            Toast.makeText(
                this,
                if (isChecked) getString(R.string.toast_auto_volume_on) else getString(R.string.toast_auto_volume_off),
                Toast.LENGTH_SHORT
            ).show()
        }

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

        binding.btnChangePlayer.setOnClickListener {
            startActivity(Intent(this, PlayerSelectorActivity::class.java))
        }

        binding.btnLaunchActivePlayer.setOnClickListener {
            MediaControlDispatcher.launchSelectedPlayer(this)
        }

        binding.btnOpenBuiltinMusic.setOnClickListener {
            startActivity(Intent(this, MusicActivity::class.java))
        }

        binding.btnSelectLanguage.setOnClickListener {
            showLanguageDialog()
        }
        binding.cardLanguageTile.setOnClickListener {
            showLanguageDialog()
        }

        binding.btnSelectTheme.setOnClickListener {
            showThemeDialog()
        }
        binding.cardThemeTile.setOnClickListener {
            showThemeDialog()
        }

        binding.btnCheckUpdate.setOnClickListener {
            Toast.makeText(this, getString(R.string.toast_checking_update), Toast.LENGTH_SHORT).show()
            AppUpdateManager.checkForUpdates(this, showUpToDateToast = true)
        }

        binding.btnGrantRoadSyncNotif.setOnClickListener {
            Toast.makeText(this, getString(R.string.toast_enable_roadsync_notif), Toast.LENGTH_LONG).show()
            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            startActivity(intent)
        }

        binding.btnGrantNotification.setOnClickListener {
            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            startActivity(intent)
        }

        binding.btnGrantBattery.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            }
        }

        binding.btnGrantLocation.setOnClickListener {
            if (!hasLocationPermission()) {
                requestLocationPermission()
            } else if (!hasBackgroundLocationPermission()) {
                requestBackgroundLocationPermission()
            }
        }

        binding.btnGrantContacts.setOnClickListener {
            requestContactsPermission()
        }

        val prefs = getSharedPreferences(NotificationAnnouncerService.PREFS_NAME, Context.MODE_PRIVATE)

        binding.switchWaCalls.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(NotificationAnnouncerService.KEY_ANNOUNCE_CALLS, isChecked).apply()
            if (isChecked && !ContactNameResolver.hasReadContactsPermission(this)) {
                requestContactsPermission()
            }
        }

        binding.switchWaMessages.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(NotificationAnnouncerService.KEY_ANNOUNCE_MESSAGES, isChecked).apply()
            updateWaMessageSubtitle()
            if (isChecked && !ContactNameResolver.hasReadContactsPermission(this)) {
                requestContactsPermission()
            }
        }

        binding.switchGpsStabilizer.setOnCheckedChangeListener { _, isChecked ->
            RoadSyncBridge.setGpsRealtimeEnabled(this, isChecked)
            if (isChecked && !hasLocationPermission()) {
                requestLocationPermission()
            }
        }

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

        binding.btnOpenHud.setOnClickListener {
            if (hasLocationPermission()) {
                RoadSyncBridge.startGpsService(this)
                startActivity(Intent(this, HudActivity::class.java))
            } else {
                requestLocationPermission()
            }
        }
    }

    private fun updateWaMessageSubtitle() {
        val prefs = getSharedPreferences(NotificationAnnouncerService.PREFS_NAME, Context.MODE_PRIVATE)
        val isManualSpeechOn = prefs.getBoolean(NotificationAnnouncerService.KEY_ANNOUNCE_MESSAGES, false)
        val isRoadSyncNotifOk = RoadSyncBridge.isRoadSyncNotificationAccessGranted(this)

        binding.tvWaMessagesModeDesc.text = when {
            isManualSpeechOn -> getString(R.string.toggle_whatsapp_messages_sub)
            isRoadSyncNotifOk -> getString(R.string.info_whatsapp_messages_roadsync_sub)
            else -> getString(R.string.toggle_whatsapp_messages_fallback_sub)
        }
    }

    private fun updateMusicPlayerSwitchUi(enabled: Boolean) {
        binding.switchHsyncMusic.isChecked = enabled
        val targetAlpha = if (enabled) 1.0f else 0.42f
        if (isReducedMotionEnabled()) {
            binding.layoutMusicPlayerControls.alpha = targetAlpha
        } else {
            binding.layoutMusicPlayerControls.animate()
                .alpha(targetAlpha)
                .setDuration(160L)
                .setInterpolator(easeOutInterpolator)
                .start()
        }

        if (enabled) {
            binding.tvHsyncMusicDesc.text = getString(R.string.switch_hsync_music_desc_on)
            binding.btnChangePlayer.isEnabled = true
            binding.btnMainPrev.isEnabled = true
            binding.btnMainPlayPause.isEnabled = true
            binding.btnMainNext.isEnabled = true
            binding.btnLaunchActivePlayer.isEnabled = true
            binding.btnOpenBuiltinMusic.isEnabled = true
        } else {
            binding.tvHsyncMusicDesc.text = getString(R.string.switch_hsync_music_desc_off)
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

    private fun setHeaderStatusPill(label: String, colorRes: Int, pillDrawableRes: Int) {
        val color = ContextCompat.getColor(this, colorRes)
        binding.tvHeaderStatusBadge.text = label
        binding.tvHeaderStatusBadge.setTextColor(color)
        ImageViewCompat.setImageTintList(binding.ivHeaderStatusDot, ColorStateList.valueOf(color))
        binding.layoutHeaderStatusPill.setBackgroundResource(pillDrawableRes)
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
            setHeaderStatusPill(
                getString(R.string.status_car_mode),
                R.color.accent_amber,
                R.drawable.bg_pill_pending
            )
        } else if (isCompanionActive) {
            binding.btnToggleCompanion.isEnabled = true
            binding.tvRoadSyncStatus.text = getString(R.string.roadsync_status_active)
            binding.tvRoadSyncStatus.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
            binding.btnToggleCompanion.text = getString(R.string.btn_turn_off)
            binding.btnToggleCompanion.setTextColor(ContextCompat.getColor(this, R.color.accent_red))
            binding.btnToggleCompanion.strokeColor = ContextCompat.getColorStateList(this, R.color.accent_red)
            setHeaderStatusPill(
                getString(R.string.status_active),
                R.color.accent_green,
                R.drawable.bg_pill_active
            )
        } else if (isInstalled) {
            binding.btnToggleCompanion.isEnabled = true
            binding.tvRoadSyncStatus.text = getString(R.string.roadsync_status_ready)
            binding.tvRoadSyncStatus.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
            binding.btnToggleCompanion.text = getString(R.string.btn_turn_on)
            binding.btnToggleCompanion.setTextColor(ContextCompat.getColor(this, R.color.accent_cyan))
            binding.btnToggleCompanion.strokeColor = ContextCompat.getColorStateList(this, R.color.accent_cyan)
            setHeaderStatusPill(
                getString(R.string.status_standby),
                R.color.text_secondary,
                R.drawable.bg_pill
            )
        } else {
            binding.btnToggleCompanion.isEnabled = true
            binding.tvRoadSyncStatus.text = getString(R.string.roadsync_status_not_installed)
            binding.tvRoadSyncStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            binding.btnToggleCompanion.text = getString(R.string.btn_turn_on)
            binding.btnToggleCompanion.setTextColor(ContextCompat.getColor(this, R.color.accent_cyan))
            binding.btnToggleCompanion.strokeColor = ContextCompat.getColorStateList(this, R.color.accent_cyan)
            setHeaderStatusPill(
                getString(R.string.status_offline),
                R.color.text_muted,
                R.drawable.bg_pill
            )
        }

        if (connectedDevice != null) {
            binding.tvBluetoothDeviceName.text = getString(R.string.bt_connected, connectedDevice)
            binding.tvBluetoothDeviceName.setTextColor(ContextCompat.getColor(this, R.color.accent_green))
        } else {
            binding.tvBluetoothDeviceName.text = getString(R.string.bt_waiting)
            binding.tvBluetoothDeviceName.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        }

        binding.switchAutoStartCompanion.isChecked = RoadSyncBridge.isAutoStartEnabled(this)
        binding.switchAutoVolume20.isChecked = RoadSyncBridge.isAutoVolume20Enabled(this)
    }

    private fun updateActivePlayerName() {
        val selectedName = MediaControlDispatcher.getSelectedName(this)
        binding.tvActivePlayerName.text = selectedName
        updateTrackInfoUi()
    }

    private fun updateTrackInfoUi() {
        val track = MediaControlDispatcher.getActiveTrackInfo(this)
        if (track != null && track.title.isNotBlank()) {
            binding.tvActiveTrackInfo.text = if (track.artist.isNotBlank()) {
                "${track.title} · ${track.artist}"
            } else {
                track.title
            }
            if (track.isPlaying) {
                binding.tvActiveTrackState.text = getString(R.string.state_playing)
                binding.tvActiveTrackState.setTextColor(ContextCompat.getColor(this, R.color.accent_green))
                binding.btnMainPlayPause.text = getString(R.string.btn_pause)
                binding.btnMainPlayPause.setIconResource(R.drawable.ic_pause)
            } else {
                binding.tvActiveTrackState.text = getString(R.string.state_paused)
                binding.tvActiveTrackState.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
                binding.btnMainPlayPause.text = getString(R.string.btn_play)
                binding.btnMainPlayPause.setIconResource(R.drawable.ic_play)
            }
        } else {
            binding.tvActiveTrackInfo.text = getString(R.string.no_music_playing)
            binding.tvActiveTrackState.text = getString(R.string.state_paused)
            binding.tvActiveTrackState.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            binding.btnMainPlayPause.text = getString(R.string.btn_play)
            binding.btnMainPlayPause.setIconResource(R.drawable.ic_play)
        }
    }

    private fun loadPreferences() {
        val prefs = getSharedPreferences(NotificationAnnouncerService.PREFS_NAME, Context.MODE_PRIVATE)
        binding.switchWaCalls.isChecked = prefs.getBoolean(NotificationAnnouncerService.KEY_ANNOUNCE_CALLS, true)
        binding.switchWaMessages.isChecked = prefs.getBoolean(NotificationAnnouncerService.KEY_ANNOUNCE_MESSAGES, false)
        binding.switchAutoVolume20.isChecked = RoadSyncBridge.isAutoVolume20Enabled(this)
        binding.switchGpsStabilizer.isChecked = RoadSyncBridge.isGpsRealtimeEnabled(this)
    }

    private fun applyPermissionRowState(
        statusView: android.widget.TextView,
        actionBtn: com.google.android.material.button.MaterialButton,
        statusText: String,
        statusColorRes: Int,
        btnText: String,
        actionable: Boolean
    ) {
        statusView.text = statusText
        statusView.setTextColor(ContextCompat.getColor(this, statusColorRes))
        actionBtn.text = btnText
        actionBtn.isEnabled = actionable
        actionBtn.alpha = if (actionable) 1.0f else 0.48f
    }

    private fun updatePermissionStatuses() {
        // 1A. RoadSync Notification Listener
        val isRoadSyncInstalled = RoadSyncBridge.isRoadSyncInstalled(this)
        val isRoadSyncNotifGranted = RoadSyncBridge.isRoadSyncNotificationAccessGranted(this)
        if (!isRoadSyncInstalled) {
            applyPermissionRowState(
                binding.tvRoadSyncNotifStatus,
                binding.btnGrantRoadSyncNotif,
                getString(R.string.status_offline),
                R.color.text_muted,
                getString(R.string.btn_configure),
                false
            )
        } else if (isRoadSyncNotifGranted) {
            applyPermissionRowState(
                binding.tvRoadSyncNotifStatus,
                binding.btnGrantRoadSyncNotif,
                getString(R.string.status_granted_roadsync_notif),
                R.color.accent_green,
                getString(R.string.btn_granted),
                false
            )
        } else {
            applyPermissionRowState(
                binding.tvRoadSyncNotifStatus,
                binding.btnGrantRoadSyncNotif,
                getString(R.string.status_grant_roadsync_notif_hint),
                R.color.accent_amber,
                getString(R.string.btn_grant),
                true
            )
        }

        // 1B. H-Sync Notification Listener
        if (isNotificationServiceEnabled()) {
            applyPermissionRowState(
                binding.tvNotificationStatus,
                binding.btnGrantNotification,
                getString(R.string.status_granted_notification),
                R.color.accent_green,
                getString(R.string.btn_granted),
                false
            )
        } else {
            applyPermissionRowState(
                binding.tvNotificationStatus,
                binding.btnGrantNotification,
                getString(R.string.status_grant_notification_hint),
                R.color.accent_amber,
                getString(R.string.btn_configure),
                true
            )
        }

        // 2. Battery Optimization
        val isBatteryIgnoring = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.isIgnoringBatteryOptimizations(packageName)
        } else true

        if (isBatteryIgnoring) {
            applyPermissionRowState(
                binding.tvBatteryStatus,
                binding.btnGrantBattery,
                getString(R.string.status_granted_battery),
                R.color.accent_green,
                getString(R.string.btn_granted),
                false
            )
        } else {
            applyPermissionRowState(
                binding.tvBatteryStatus,
                binding.btnGrantBattery,
                getString(R.string.status_grant_battery_hint),
                R.color.accent_amber,
                getString(R.string.btn_configure),
                true
            )
        }

        // 3. Location & Background Location
        if (hasLocationPermission() && hasBackgroundLocationPermission()) {
            applyPermissionRowState(
                binding.tvLocationStatus,
                binding.btnGrantLocation,
                getString(R.string.status_granted_location_bg),
                R.color.accent_green,
                getString(R.string.btn_granted),
                false
            )
        } else if (hasLocationPermission()) {
            applyPermissionRowState(
                binding.tvLocationStatus,
                binding.btnGrantLocation,
                getString(R.string.status_grant_location_bg_hint),
                R.color.accent_cyan,
                getString(R.string.btn_allow_always),
                true
            )
        } else {
            applyPermissionRowState(
                binding.tvLocationStatus,
                binding.btnGrantLocation,
                getString(R.string.status_grant_location_hint),
                R.color.accent_amber,
                getString(R.string.btn_configure),
                true
            )
        }

        // 4. Caller Contacts
        if (ContactNameResolver.hasReadContactsPermission(this)) {
            applyPermissionRowState(
                binding.tvContactsStatus,
                binding.btnGrantContacts,
                getString(R.string.status_granted_contacts),
                R.color.accent_green,
                getString(R.string.btn_granted),
                false
            )
        } else {
            applyPermissionRowState(
                binding.tvContactsStatus,
                binding.btnGrantContacts,
                getString(R.string.status_grant_contacts_hint),
                R.color.accent_amber,
                getString(R.string.btn_configure),
                true
            )
        }
    }

    private fun requestEssentialPermissionsIfNeeded() {
        val missing = mutableListOf<String>()
        if (!hasLocationPermission()) {
            missing.add(Manifest.permission.ACCESS_FINE_LOCATION)
            missing.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if (!ContactNameResolver.hasReadContactsPermission(this)) {
            missing.add(Manifest.permission.READ_CONTACTS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                missing.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                missing.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), runtimePermissionReqCode)
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

    private fun hasBackgroundLocationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun requestLocationPermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ),
            runtimePermissionReqCode
        )
    }

    private fun requestBackgroundLocationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
                backgroundLocationReqCode
            )
        }
    }

    private fun requestContactsPermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.READ_CONTACTS),
            runtimePermissionReqCode
        )
    }
}

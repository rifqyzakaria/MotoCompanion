package com.motocompanion.util

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.motocompanion.service.GpsTrackingService
import com.motocompanion.service.MediaSessionService
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.math.roundToInt

object RoadSyncBridge {

    private const val TAG = "RoadSyncBridge"

    // Package resmi Honda RoadSync & Google Maps
    const val PKG_HONDA_ROADSYNC = "com.honda.ms.dm.sab"
    const val PKG_GOOGLE_MAPS = "com.google.android.apps.maps"

    val KNOWN_ROADSYNC_PACKAGES = listOf(
        "com.honda.ms.dm.sab",
        "com.honda.ms.hsvcs",
        "com.honda.auto.hsvcs"
    )

    private const val PREFS_NAME = "moto_roadsync_prefs"
    const val KEY_AUTO_START_BT = "auto_start_on_bluetooth"
    const val KEY_COMPANION_ACTIVE = "companion_service_active"
    const val KEY_MUSIC_PLAYER_ENABLED = "key_music_player_bridge_enabled"
    const val KEY_AUTO_VOLUME_20 = "key_auto_music_volume_20_percent"
    const val KEY_GPS_REALTIME_ENABLED = "key_gps_realtime_maps_enabled"

    const val ACTION_ROADSYNC_STATE_CHANGED = "com.motocompanion.ROADSYNC_STATE_CHANGED"
    const val ACTION_CAR_CONNECTION_UPDATED = "androidx.car.app.connection.action.CAR_CONNECTION_UPDATED"

    private const val TARGET_HEADUNIT_VOLUME_RATIO = 0.20f

    @Volatile
    var isRoadSyncActive: Boolean = false

    private val activeConnectedTargetDevices = CopyOnWriteArraySet<String>()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var sessionConnectTimestampMs: Long = 0L

    @Volatile
    private var hasAppliedSessionVolume: Boolean = false

    /**
     * Memeriksa apakah ponsel sedang terhubung ke mobil / Android Auto (Head Unit Mobil).
     * Jika terhubung, H-Sync tidak boleh berjalan agar tidak bentrok dengan sistem mobil.
     */
    fun isAndroidAutoConnected(context: Context): Boolean {
        try {
            val uri = Uri.parse("content://androidx.car.app.connection")
            context.contentResolver.query(uri, arrayOf("CarConnectionState"), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val colIndex = cursor.getColumnIndex("CarConnectionState")
                    if (colIndex != -1) {
                        val state = cursor.getInt(colIndex)
                        if (state == 1 || state == 2) {
                            Log.i(TAG, "🚗 Android Auto terdeteksi aktif (state=$state)")
                            return true
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        try {
            val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as? android.app.UiModeManager
            if (uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_CAR) {
                Log.i(TAG, "🚗 Car Mode terdeteksi aktif via UiModeManager")
                return true
            }
        } catch (_: Exception) {}

        return false
    }

    fun isMusicPlayerEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_MUSIC_PLAYER_ENABLED, true)
    }

    fun setMusicPlayerEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_MUSIC_PLAYER_ENABLED, enabled).apply()

        val pm = context.packageManager
        val state = if (enabled) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }

        val components = listOf(
            ComponentName(context, MediaSessionService::class.java),
            ComponentName(context, "com.spotify.music.libs.mediabrowserservice.SpotifyMediaBrowserService"),
            ComponentName(context, "com.spotify.mediabrowserservice.SpotifyMediaBrowserService"),
            ComponentName(context, "com.spotify.mediabrowserservice.mediabrowserservice.SpotifyMediaBrowserService")
        )

        for (comp in components) {
            try {
                pm.setComponentEnabledSetting(comp, state, PackageManager.DONT_KILL_APP)
            } catch (e: Exception) {
                Log.w(TAG, "Gagal mengubah status komponen ${comp.className}: ${e.message}")
            }
        }

        if (!enabled) {
            try {
                val stopIntent = Intent(context, MediaSessionService::class.java).apply {
                    action = MediaSessionService.ACTION_STOP_SERVICE
                }
                context.startService(stopIntent)
                MediaSessionService.sharedMediaSession?.isActive = false
            } catch (_: Exception) {}
        } else {
            if (isCompanionActive(context) || isRoadSyncActive || getConnectedDeviceName(context) != null) {
                startMediaService(context)
            }
        }
    }

    fun isAutoVolume20Enabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_AUTO_VOLUME_20, true)
    }

    fun setAutoVolume20Enabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_AUTO_VOLUME_20, enabled).apply()
        if (enabled && (isRoadSyncActive || getConnectedDeviceName(context) != null)) {
            hasAppliedSessionVolume = false
            applyHeadunitMusicVolume(context, forceApply = true)
        }
    }

    fun isGpsRealtimeEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_GPS_REALTIME_ENABLED, true)
    }

    fun setGpsRealtimeEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_GPS_REALTIME_ENABLED, enabled).apply()
        if (enabled && (isRoadSyncActive || isCompanionActive(context) || getConnectedDeviceName(context) != null)) {
            startGpsService(context)
        } else if (!enabled) {
            try {
                context.stopService(Intent(context, GpsTrackingService::class.java))
            } catch (_: Exception) {}
        }
    }

    /**
     * Menurunkan volume pemutar musik (STREAM_MUSIC) ke 20% secara otomatis saat terhubung ke Headunit motor.
     * Menangani penundaan routing Bluetooth A2DP Absolute Volume agar tidak kembali ke 100%,
     * namun hanya diterapkan pada awal sesi koneksi sehingga pengendara tetap bebas mengatur volume manual setelahnya.
     */
    fun applyHeadunitMusicVolume(context: Context, forceApply: Boolean = false) {
        if (!isAutoVolume20Enabled(context)) return

        val now = System.currentTimeMillis()
        val isWithinInitialConnectWindow = (now - sessionConnectTimestampMs) <= 15_000L

        if (!forceApply && hasAppliedSessionVolume && !isWithinInitialConnectWindow) {
            return
        }

        val appCtx = context.applicationContext
        setMusicStreamTo20Percent(appCtx)

        if (!hasAppliedSessionVolume || forceApply) {
            hasAppliedSessionVolume = true
            sessionConnectTimestampMs = now
            // Re-verifikasi setelah jalur Bluetooth A2DP selesai berpindah dari speaker HP ke intercom/headunit
            mainHandler.postDelayed({
                if (isRoadSyncActive || isCompanionActive(appCtx)) {
                    setMusicStreamTo20Percent(appCtx)
                }
            }, 1200L)
            mainHandler.postDelayed({
                if (isRoadSyncActive || isCompanionActive(appCtx)) {
                    setMusicStreamTo20Percent(appCtx)
                }
            }, 2600L)
        }
    }

    private fun setMusicStreamTo20Percent(context: Context) {
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            if (maxVol <= 0) return

            val targetVol = (maxVol * TARGET_HEADUNIT_VOLUME_RATIO).roundToInt().coerceIn(1, maxVol)
            val currentVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)

            if (currentVol != targetVol) {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVol, 0)
                Log.i(TAG, "🔉 Volume musik disesuaikan ke 20% ($targetVol / $maxVol) untuk Headunit motor")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Gagal mengatur volume musik ke 20%: ${e.message}")
        }
    }

    fun onBluetoothDeviceConnected(deviceName: String) {
        if (deviceName.isNotBlank() && isTargetDeviceName(deviceName)) {
            activeConnectedTargetDevices.add(deviceName)
        }
    }

    fun onBluetoothDeviceDisconnected(deviceName: String) {
        if (deviceName.isNotBlank()) {
            activeConnectedTargetDevices.remove(deviceName)
        }
    }

    fun isTargetDeviceName(name: String?): Boolean {
        if (name == null) return false
        val lower = name.lowercase()
        return lower.contains("honda") || lower.contains("roadsync") ||
                lower.contains("hsvcs") || lower.contains("btu") ||
                lower.contains("pcx") || lower.contains("adv") ||
                lower.contains("forza") || lower.contains("hornet") ||
                lower.contains("transalp") || lower.contains("cb") ||
                lower.contains("cbr") || lower.contains("rebel") ||
                lower.contains("hawk") || lower.contains("africa") ||
                lower.contains("scooter") || lower.contains("intercom") ||
                lower.contains("sena") || lower.contains("cardo") ||
                lower.contains("ejeas") || lower.contains("freedconn") ||
                lower.contains("helmet") || lower.contains("headset")
    }

    fun isAutoStartEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_AUTO_START_BT, true)
    }

    fun setAutoStartEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_AUTO_START_BT, enabled).apply()
    }

    fun isCompanionActive(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_COMPANION_ACTIVE, false)
    }

    fun setCompanionActive(context: Context, active: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_COMPANION_ACTIVE, active).apply()
    }

    /**
     * Otomatis mengaktifkan seluruh ekosistem H-Sync saat Headunit motor Honda / RoadSync tersambung:
     * 1. Menurunkan volume musik ke 20%
     * 2. Mengunci GPS Realtime (Google Maps Fused Location + GNSS Hardware) di latar belakang
     * 3. Menjalankan jembatan pemutar musik (jika diaktifkan)
     */
    fun startAllServices(context: Context, triggerSource: String = "") {
        if (isAndroidAutoConnected(context)) {
            Log.w(TAG, "🛑 Android Auto / Mobil terdeteksi ($triggerSource). H-Sync dicegah agar tidak berjalan.")
            stopAllServices(context, "Android Auto Terhubung")
            return
        }

        val wasAlreadyActive = isRoadSyncActive
        Log.i(TAG, "🟢 Mengaktifkan H-Sync (Trigger: $triggerSource, wasActive=$wasAlreadyActive)")
        isRoadSyncActive = true
        setCompanionActive(context, true)

        // 1. Turunkan volume pemutar musik ke 20% pada saat connect ke Headunit motor
        if (!wasAlreadyActive) {
            sessionConnectTimestampMs = System.currentTimeMillis()
            hasAppliedSessionVolume = false
        }
        applyHeadunitMusicVolume(context)

        // 2. Langsung aktifkan GPS Realtime (Google Maps Fused Engine) agar langsung lock & anti telat Turn-by-Turn
        if (isGpsRealtimeEnabled(context)) {
            startGpsService(context, boostNavigation = true)
        }

        // 3. Aktifkan MediaSessionService jika saklar pemutar musik H-Sync ON
        if (isMusicPlayerEnabled(context)) {
            startMediaService(context)
        } else {
            Log.i(TAG, "Pemutar musik H-Sync dinonaktifkan (RoadSync memakai pemutar bawaan).")
        }

        val broadcast = Intent(ACTION_ROADSYNC_STATE_CHANGED).setPackage(context.packageName)
        context.sendBroadcast(broadcast)
    }

    fun startGpsService(context: Context, boostNavigation: Boolean = false) {
        val hasFine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!hasFine && !hasCoarse) {
            Log.w(TAG, "Izin lokasi belum diberikan, menunda start GpsTrackingService")
            return
        }

        val gpsIntent = Intent(context, GpsTrackingService::class.java).apply {
            if (boostNavigation) {
                action = GpsTrackingService.ACTION_BOOST_NAVIGATION
            }
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(context, gpsIntent)
            } else {
                context.startService(gpsIntent)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Tidak dapat memulai GpsTrackingService dari latar belakang: ${e.message}")
        }
    }

    fun startMediaService(context: Context) {
        val mediaServiceIntent = Intent(context, MediaSessionService::class.java).apply {
            action = MediaSessionService.ACTION_START_FOREGROUND
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(context, mediaServiceIntent)
            } else {
                context.startService(mediaServiceIntent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Gagal memulai MediaSessionService: ${e.message}")
        }
    }

    /**
     * Otomatis mematikan H-Sync secara bersih saat motor Honda RoadSync mati / terputus
     */
    fun stopAllServices(context: Context, triggerSource: String = "") {
        Log.i(TAG, "🔴 Mematikan H-Sync secara otomatis (Trigger: $triggerSource)")
        isRoadSyncActive = false
        hasAppliedSessionVolume = false
        sessionConnectTimestampMs = 0L
        setCompanionActive(context, false)
        MediaSessionService.setIncomingWhatsAppCaller(null)

        val mediaServiceIntent = Intent(context, MediaSessionService::class.java).apply {
            action = MediaSessionService.ACTION_STOP_SERVICE
        }
        try {
            context.startService(mediaServiceIntent)
        } catch (_: Exception) {
            try {
                context.stopService(Intent(context, MediaSessionService::class.java))
            } catch (_: Exception) {}
        }

        try {
            context.stopService(Intent(context, GpsTrackingService::class.java))
        } catch (_: Exception) {}

        val broadcast = Intent(ACTION_ROADSYNC_STATE_CHANGED).setPackage(context.packageName)
        context.sendBroadcast(broadcast)
    }

    fun isRoadSyncInstalled(context: Context): Boolean {
        val pm = context.packageManager
        for (pkg in KNOWN_ROADSYNC_PACKAGES) {
            try {
                pm.getPackageInfo(pkg, 0)
                return true
            } catch (_: PackageManager.NameNotFoundException) {}
        }
        return false
    }

    fun getInstalledRoadSyncPackage(context: Context): String? {
        val pm = context.packageManager
        for (pkg in KNOWN_ROADSYNC_PACKAGES) {
            try {
                pm.getPackageInfo(pkg, 0)
                return pkg
            } catch (_: PackageManager.NameNotFoundException) {}
        }
        return null
    }

    fun launchRoadSync(context: Context): Boolean {
        val pkg = getInstalledRoadSyncPackage(context) ?: PKG_HONDA_ROADSYNC
        val launchIntent = context.packageManager.getLaunchIntentForPackage(pkg)
        return if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launchIntent)
            true
        } else {
            false
        }
    }

    /**
     * Mengambil nama perangkat Bluetooth motor Honda atau helm yang BENAR-BENAR SEDANG TERHUBUNG aktif
     * (bukan sekadar pernah dipasangkan / paired di masa lalu).
     */
    fun getConnectedDeviceName(context: Context): String? {
        if (activeConnectedTargetDevices.isNotEmpty()) {
            return activeConnectedTargetDevices.firstOrNull()
        }

        try {
            val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager ?: return null
            val adapter = bm.adapter ?: return null
            if (!adapter.isEnabled) return null

            // Cek perangkat BLE / GATT yang aktif terhubung (seperti modul BLE Honda RoadSync HSVCS)
            try {
                val gattDevices = bm.getConnectedDevices(BluetoothProfile.GATT)
                for (device in gattDevices) {
                    val name = try { device.name ?: "" } catch (_: SecurityException) { "" }
                    if (isTargetDeviceName(name)) {
                        activeConnectedTargetDevices.add(name)
                        return name
                    }
                }
            } catch (_: SecurityException) {} catch (_: Exception) {}

            // Cek perangkat Classic Bluetooth (A2DP / HFP / ACL) yang benar-benar berstatus connected
            val bondedDevices: Set<BluetoothDevice>? = try {
                adapter.bondedDevices
            } catch (_: SecurityException) {
                null
            }

            bondedDevices?.forEach { device ->
                val name = try { device.name ?: "" } catch (_: SecurityException) { "" }
                if (isTargetDeviceName(name) && isBluetoothDeviceActuallyConnected(device)) {
                    activeConnectedTargetDevices.add(name)
                    return name
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error checking connected bluetooth: ${e.message}")
        }
        return null
    }

    private fun isBluetoothDeviceActuallyConnected(device: BluetoothDevice): Boolean {
        return try {
            val method = BluetoothDevice::class.java.getMethod("isConnected")
            (method.invoke(device) as? Boolean) == true
        } catch (_: Exception) {
            false
        }
    }
}

package com.motocompanion.util

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.motocompanion.service.GpsTrackingService
import com.motocompanion.service.MediaSessionService

object RoadSyncBridge {

    private const val TAG = "RoadSyncBridge"

    // Package resmi Honda RoadSync (sebelumnya Drivemode SAB)
    const val PKG_HONDA_ROADSYNC = "com.honda.ms.dm.sab"
    val KNOWN_ROADSYNC_PACKAGES = listOf(
        "com.honda.ms.dm.sab",
        "com.honda.ms.hsvcs",
        "com.honda.auto.hsvcs"
    )

    private const val PREFS_NAME = "moto_roadsync_prefs"
    const val KEY_AUTO_START_BT = "auto_start_on_bluetooth"
    const val KEY_COMPANION_ACTIVE = "companion_service_active"
    const val KEY_MUSIC_PLAYER_ENABLED = "key_music_player_bridge_enabled"
    const val ACTION_ROADSYNC_STATE_CHANGED = "com.motocompanion.ROADSYNC_STATE_CHANGED"

    var isRoadSyncActive: Boolean = false

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
     * Otomatis mengaktifkan H-Sync saat motor Honda RoadSync / Bluetooth tersambung
     */
    fun startAllServices(context: Context, triggerSource: String = "") {
        Log.i(TAG, "🟢 Mengaktifkan H-Sync secara otomatis (Trigger: $triggerSource)")
        isRoadSyncActive = true
        setCompanionActive(context, true)

        if (isMusicPlayerEnabled(context)) {
            startMediaService(context)
        } else {
            Log.i(TAG, "Pemutar musik H-Sync dinonaktifkan (RoadSync memakai pemutar bawaan).")
        }

        context.sendBroadcast(Intent(ACTION_ROADSYNC_STATE_CHANGED))
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
        setCompanionActive(context, false)

        // 1. Hentikan MediaSessionService dan bersihkan notifikasi foreground
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

        // 2. Hentikan GpsTrackingService
        try {
            context.stopService(Intent(context, GpsTrackingService::class.java))
        } catch (_: Exception) {}

        context.sendBroadcast(Intent(ACTION_ROADSYNC_STATE_CHANGED))
    }

    /**
     * Memeriksa apakah aplikasi Honda RoadSync terinstal di ponsel
     */
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

    /**
     * Membuka aplikasi Honda RoadSync
     */
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
     * Mengambil nama perangkat Bluetooth motor Honda atau helm yang sedang terhubung
     */
    fun getConnectedDeviceName(context: Context): String? {
        try {
            val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager ?: return null
            val adapter = bm.adapter ?: return null
            if (!adapter.isEnabled) return null

            val bondedDevices: Set<BluetoothDevice>? = try {
                adapter.bondedDevices
            } catch (e: SecurityException) {
                null
            }

            bondedDevices?.forEach { device ->
                val name = try { device.name ?: "" } catch (_: SecurityException) { "" }
                if (isTargetDeviceName(name)) {
                    return name
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error checking connected bluetooth: ${e.message}")
        }
        return null
    }
}

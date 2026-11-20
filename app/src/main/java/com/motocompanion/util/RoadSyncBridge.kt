package com.motocompanion.util

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log

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
    const val ACTION_ROADSYNC_STATE_CHANGED = "com.motocompanion.ROADSYNC_STATE_CHANGED"

    var isRoadSyncActive: Boolean = false

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
     * Mengambil nama perangkat Bluetooth motor PCX atau helm yang sedang terhubung
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
                val lower = name.lowercase()
                if (lower.contains("honda") || lower.contains("pcx") || lower.contains("adv") ||
                    lower.contains("hsvcs") || lower.contains("roadsync") || lower.contains("forza") ||
                    lower.contains("sena") || lower.contains("cardo") || lower.contains("ejeas") ||
                    lower.contains("freedconn") || lower.contains("intercom") || lower.contains("helmet")) {
                    return name
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error checking connected bluetooth: ${e.message}")
        }
        return null
    }
}

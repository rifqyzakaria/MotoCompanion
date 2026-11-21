package com.motocompanion.receiver

import android.app.UiModeManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import com.motocompanion.R
import com.motocompanion.util.RoadSyncBridge

/**
 * Receiver khusus untuk mendeteksi saat HP terhubung ke Android Auto / Mobil.
 * Ketika HP terhubung ke Android Auto, H-Sync akan otomatis dimatikan dan tidak berjalan,
 * sehingga tidak mengganggu sistem audio atau navigasi mobil.
 */
class CarConnectionReceiver : BroadcastReceiver() {

    private val tag = "MotoCarReceiver"

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        val action = intent.action
        Log.i(tag, "Car connection broadcast received: $action")

        val isCarConnected = RoadSyncBridge.isAndroidAutoConnected(context)
        if (isCarConnected || action == UiModeManager.ACTION_ENTER_CAR_MODE) {
            Log.i(tag, "🚗 HP terhubung ke Mobil / Android Auto. Mematikan H-Sync secara bersih.")
            RoadSyncBridge.stopAllServices(context, "Android Auto Terhubung")
            try {
                Toast.makeText(
                    context,
                    context.getString(R.string.status_android_auto_toast),
                    Toast.LENGTH_SHORT
                ).show()
            } catch (_: Exception) {}
        } else if (action == UiModeManager.ACTION_EXIT_CAR_MODE) {
            Log.i(tag, "Keluar dari mode mobil. Memeriksa koneksi motor Honda...")
            val isBikeConnected = RoadSyncBridge.getConnectedDeviceName(context) != null
            if (isBikeConnected && RoadSyncBridge.isAutoStartEnabled(context)) {
                RoadSyncBridge.startAllServices(context, "Keluar dari Android Auto & Motor Honda Terhubung")
            }
        }
    }
}

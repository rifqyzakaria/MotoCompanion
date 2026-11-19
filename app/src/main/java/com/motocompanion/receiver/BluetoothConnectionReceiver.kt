package com.motocompanion.receiver

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.motocompanion.service.GpsTrackingService
import com.motocompanion.service.MediaSessionService
import com.motocompanion.util.RoadSyncBridge

class BluetoothConnectionReceiver : BroadcastReceiver() {

    private val tag = "MotoBtReceiver"

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return

        val action = intent.action
        @Suppress("DEPRECATION")
        val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }

        val deviceName = try { device?.name ?: "" } catch (_: SecurityException) { "" }
        val lowerName = deviceName.lowercase()

        val isTargetDevice = lowerName.contains("honda") || lowerName.contains("pcx") ||
                lowerName.contains("adv") || lowerName.contains("hsvcs") ||
                lowerName.contains("roadsync") || lowerName.contains("sena") ||
                lowerName.contains("cardo") || lowerName.contains("ejeas") ||
                lowerName.contains("freedconn") || lowerName.contains("intercom") ||
                lowerName.contains("helmet")

        if (BluetoothDevice.ACTION_ACL_CONNECTED == action) {
            Log.i(tag, "Bluetooth connected: $deviceName (isTarget=$isTargetDevice)")

            if (isTargetDevice && RoadSyncBridge.isAutoStartEnabled(context)) {
                RoadSyncBridge.setCompanionActive(context, true)

                // 1. Jalankan MediaSessionService di foreground agar terhubung ke RoadSync
                val mediaServiceIntent = Intent(context, MediaSessionService::class.java).apply {
                    action = MediaSessionService.ACTION_TOGGLE
                }
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        ContextCompat.startForegroundService(context, mediaServiceIntent)
                    } else {
                        context.startService(mediaServiceIntent)
                    }
                } catch (e: Exception) {
                    Log.e(tag, "Gagal memulai MediaSessionService: ${e.message}")
                }

                // 2. Jalankan GPS service jika izin lokasi sudah ada
                val hasLocation = ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED

                if (hasLocation) {
                    val gpsIntent = Intent(context, GpsTrackingService::class.java)
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            ContextCompat.startForegroundService(context, gpsIntent)
                        } else {
                            context.startService(gpsIntent)
                        }
                    } catch (e: Exception) {
                        Log.e(tag, "Gagal memulai GpsTrackingService: ${e.message}")
                    }
                }

                Toast.makeText(
                    context,
                    "🏍️ Terhubung ke $deviceName! MotoCompanion mendampingi Honda RoadSync.",
                    Toast.LENGTH_LONG
                ).show()
            }
        } else if (BluetoothDevice.ACTION_ACL_DISCONNECTED == action) {
            Log.i(tag, "Bluetooth disconnected: $deviceName")
            if (isTargetDevice) {
                RoadSyncBridge.setCompanionActive(context, false)
            }
        }
    }
}

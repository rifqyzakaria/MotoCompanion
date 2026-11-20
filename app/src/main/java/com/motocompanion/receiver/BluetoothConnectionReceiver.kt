package com.motocompanion.receiver

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import android.widget.Toast
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
        val isTargetDevice = RoadSyncBridge.isTargetDeviceName(deviceName)

        if (BluetoothDevice.ACTION_ACL_CONNECTED == action) {
            Log.i(tag, "Bluetooth connected: $deviceName (isTarget=$isTargetDevice)")

            if (isTargetDevice && RoadSyncBridge.isAutoStartEnabled(context)) {
                // Otomatis aktifkan H-Sync saat motor Honda / helm terhubung
                RoadSyncBridge.startAllServices(context, "Bluetooth $deviceName")

                Toast.makeText(
                    context,
                    "🏍️ Terhubung ke $deviceName! H-Sync siap mendampingi Honda RoadSync.",
                    Toast.LENGTH_LONG
                ).show()
            }
        } else if (BluetoothDevice.ACTION_ACL_DISCONNECTED == action) {
            Log.i(tag, "Bluetooth disconnected: $deviceName (isTarget=$isTargetDevice)")

            if (isTargetDevice) {
                // Otomatis matikan H-Sync dan bersihkan background saat motor dimatikan
                RoadSyncBridge.stopAllServices(context, "Bluetooth $deviceName terputus")

                Toast.makeText(
                    context,
                    "🏍️ Terputus dari $deviceName. H-Sync dinonaktifkan otomatis.",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }
}

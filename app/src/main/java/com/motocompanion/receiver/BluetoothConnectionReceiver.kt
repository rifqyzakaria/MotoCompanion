package com.motocompanion.receiver

import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
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

        when (action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> {
                Log.i(tag, "Bluetooth ACL connected: $deviceName (isTarget=$isTargetDevice)")

                if (RoadSyncBridge.isAndroidAutoConnected(context)) {
                    Log.i(tag, "🚗 Android Auto / Mobil aktif. H-Sync tidak dijalankan.")
                    RoadSyncBridge.stopAllServices(context, "Android Auto aktif")
                    return
                }

                if (isTargetDevice) {
                    RoadSyncBridge.onBluetoothDeviceConnected(deviceName)
                    if (RoadSyncBridge.isAutoStartEnabled(context)) {
                        RoadSyncBridge.startAllServices(context, "Bluetooth Headunit $deviceName")

                        Toast.makeText(
                            context,
                            "🏍️ Terhubung ke $deviceName! Volume 20% & GPS Realtime aktif.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }

            BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED -> {
                val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_DISCONNECTED)
                if (state == BluetoothProfile.STATE_CONNECTED && isTargetDevice && !RoadSyncBridge.isAndroidAutoConnected(context)) {
                    Log.i(tag, "Bluetooth A2DP audio route aktif untuk $deviceName -> Menerapkan volume 20%")
                    RoadSyncBridge.onBluetoothDeviceConnected(deviceName)
                    RoadSyncBridge.applyHeadunitMusicVolume(context)
                }
            }

            BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                Log.i(tag, "Bluetooth ACL disconnected: $deviceName (isTarget=$isTargetDevice)")

                if (isTargetDevice) {
                    RoadSyncBridge.onBluetoothDeviceDisconnected(deviceName)
                    // Jika tidak ada perangkat motor/helm lain yang masih terhubung, matikan H-Sync bersih
                    if (RoadSyncBridge.getConnectedDeviceName(context) == null) {
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
    }
}

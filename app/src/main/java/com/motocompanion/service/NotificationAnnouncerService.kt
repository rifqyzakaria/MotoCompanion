package com.motocompanion.service

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.motocompanion.App
import com.motocompanion.R
import com.motocompanion.tts.SlangNormalizer
import com.motocompanion.util.RoadSyncBridge

class NotificationAnnouncerService : NotificationListenerService() {

    private val tag = "MotoNotificationService"

    companion object {
        const val PREFS_NAME = "moto_companion_prefs"
        const val KEY_ANNOUNCE_CALLS = "announce_whatsapp_calls"
        const val KEY_ANNOUNCE_MESSAGES = "announce_whatsapp_messages"

        private const val WHATSAPP_PKG = "com.whatsapp"
        private const val WHATSAPP_BUSINESS_PKG = "com.whatsapp.w4b"

        private var lastCallAnnouncementTime = 0L
        private var lastCallerName = ""
        private const val CALL_COOLDOWN_MS = 4000L // Jeda agar tidak spam nama pemanggil tiap detik

        private var lastMessageText = ""
        private var lastMessageTime = 0L
        private const val MESSAGE_COOLDOWN_MS = 3000L
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return

        val pkg = sbn.packageName ?: return

        // 1. Deteksi saat Honda RoadSync dibuka / aktif di latar depan -> Auto-Start H-Sync
        if (RoadSyncBridge.KNOWN_ROADSYNC_PACKAGES.contains(pkg)) {
            RoadSyncBridge.startAllServices(this, "Notifikasi RoadSync ($pkg)")
            return
        }

        // 2. Hanya tangani notifikasi dari WhatsApp / WhatsApp Business
        if (pkg != WHATSAPP_PKG && pkg != WHATSAPP_BUSINESS_PKG) return

        // 3. ATURAN UTAMA: HANYA bacakan pesan atau telepon WhatsApp jika Honda RoadSync sedang AKTIF
        if (!isRoadSyncCurrentlyActive()) {
            Log.d(tag, "Notifikasi WhatsApp diabaikan karena Honda RoadSync sedang tidak aktif")
            return
        }

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val announceCalls = prefs.getBoolean(KEY_ANNOUNCE_CALLS, true)
        val announceMessages = prefs.getBoolean(KEY_ANNOUNCE_MESSAGES, true)

        val extras = sbn.notification?.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val category = sbn.notification?.category ?: ""

        val isCallNotification = isWhatsAppCall(sbn.notification, title, text, category)

        if (isCallNotification) {
            if (announceCalls) {
                handleIncomingCall(title, text)
            }
        } else if (announceMessages && text.isNotBlank()) {
            handleIncomingMessage(title, text)
        }
    }

    /**
     * Memeriksa apakah Honda RoadSync sedang aktif (berjalan di background/foreground atau tersambung PCX)
     */
    private fun isRoadSyncCurrentlyActive(): Boolean {
        // Cek 1: Flag RoadSync aktif saat notifikasi RoadSync berjalan
        if (RoadSyncBridge.isRoadSyncActive) return true

        // Cek 2: Cek apakah ada notifikasi RoadSync yang sedang aktif di status bar
        val hasRoadSyncNotification = try {
            activeNotifications?.any { sbn ->
                RoadSyncBridge.KNOWN_ROADSYNC_PACKAGES.contains(sbn.packageName)
            } ?: false
        } catch (_: Exception) {
            false
        }
        if (hasRoadSyncNotification) {
            RoadSyncBridge.isRoadSyncActive = true
            return true
        }

        // Cek 3: Cek apakah Bluetooth motor PCX atau helm intercom sedang terhubung
        if (RoadSyncBridge.getConnectedDeviceName(this) != null) {
            return true
        }

        // Cek 4: Status companion aktif di preferences
        return RoadSyncBridge.isCompanionActive(this)
    }

    private fun isWhatsAppCall(notification: Notification?, title: String, text: String, category: String): Boolean {
        // Cek kategori panggilan resmi Android
        if (category == Notification.CATEGORY_CALL) return true

        // Cek pola teks panggilan WhatsApp dalam Bahasa Indonesia & Inggris
        val combined = "$title $text".lowercase()
        return combined.contains("panggilan suara masuk") ||
                combined.contains("panggilan video masuk") ||
                combined.contains("panggilan masuk") ||
                combined.contains("incoming voice call") ||
                combined.contains("incoming video call") ||
                combined.contains("incoming call")
    }

    private fun handleIncomingCall(title: String, text: String) {
        val now = System.currentTimeMillis()

        // Ambil nama penelepon (biasanya ada di Title atau Text)
        var caller = title.trim()
        if (caller.lowercase().contains("panggilan") || caller.lowercase().contains("incoming") || caller.isBlank()) {
            caller = text.trim()
        }

        // Hilangkan kata-kata keterangan bawaan WA
        caller = caller
            .replace("Panggilan masuk", "", ignoreCase = true)
            .replace("Incoming call", "", ignoreCase = true)
            .replace("Panggilan suara masuk", "", ignoreCase = true)
            .replace("Incoming voice call", "", ignoreCase = true)
            .replace("Panggilan video masuk", "", ignoreCase = true)
            .trim()

        if (caller.isBlank()) {
            caller = getString(R.string.unknown_caller)
        }

        // Cegah spam suara saat nada dering terus memperbarui notifikasi
        if (caller == lastCallerName && (now - lastCallAnnouncementTime) < CALL_COOLDOWN_MS) {
            return
        }

        lastCallerName = caller
        lastCallAnnouncementTime = now

        val speech = getString(R.string.tts_incoming_call, caller)
        Log.i(tag, "Mengumumkan panggilan: $speech")

        App.instance.ttsManager.speak(speech, isPriority = true)
    }

    private fun handleIncomingMessage(sender: String, message: String) {
        val now = System.currentTimeMillis()

        if (message == lastMessageText && (now - lastMessageTime) < MESSAGE_COOLDOWN_MS) {
            return
        }

        lastMessageText = message
        lastMessageTime = now

        val cleanSender = if (sender.isNotBlank()) sender else "WhatsApp"
        val cleanBody = SlangNormalizer.normalize(message)

        val speech = getString(R.string.tts_incoming_message, cleanSender, cleanBody)
        Log.i(tag, "Membacakan pesan: $speech")

        App.instance.ttsManager.speak(speech, isPriority = false)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        val pkg = sbn?.packageName ?: return

        // Jika notifikasi Honda RoadSync ditutup/hilang
        if (RoadSyncBridge.KNOWN_ROADSYNC_PACKAGES.contains(pkg)) {
            val stillPresent = try {
                activeNotifications?.any { RoadSyncBridge.KNOWN_ROADSYNC_PACKAGES.contains(it.packageName) } ?: false
            } catch (_: Exception) {
                false
            }

            if (!stillPresent && RoadSyncBridge.getConnectedDeviceName(this) == null) {
                RoadSyncBridge.stopAllServices(this, "Notifikasi RoadSync ditutup")
            }
        }

        // Reset state jika panggilan selesai / dibatalkan
        if (pkg == WHATSAPP_PKG || pkg == WHATSAPP_BUSINESS_PKG) {
            val category = sbn.notification?.category ?: ""
            if (category == Notification.CATEGORY_CALL) {
                lastCallerName = ""
            }
        }
    }
}

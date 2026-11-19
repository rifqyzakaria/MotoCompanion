package com.motocompanion.service

import android.app.Notification
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.motocompanion.App
import com.motocompanion.tts.SlangNormalizer

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

        // Deteksi aktivitas Honda RoadSync
        if (com.motocompanion.util.RoadSyncBridge.KNOWN_ROADSYNC_PACKAGES.contains(pkg)) {
            com.motocompanion.util.RoadSyncBridge.isRoadSyncActive = true
            Log.i(tag, "Notifikasi Honda RoadSync terdeteksi aktif: $pkg")
        }

        if (pkg != WHATSAPP_PKG && pkg != WHATSAPP_BUSINESS_PKG) return

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val announceCalls = prefs.getBoolean(KEY_ANNOUNCE_CALLS, true)
        val announceMessages = prefs.getBoolean(KEY_ANNOUNCE_MESSAGES, false)

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
            caller = "Nomor Tidak Dikenal"
        }

        // Cegah spam suara saat nada dering terus memperbarui notifikasi
        if (caller == lastCallerName && (now - lastCallAnnouncementTime) < CALL_COOLDOWN_MS) {
            return
        }

        lastCallerName = caller
        lastCallAnnouncementTime = now

        val speech = "Telepon WhatsApp masuk dari $caller"
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

        val speech = "Pesan dari $cleanSender: $cleanBody"
        Log.i(tag, "Membacakan pesan: $speech")

        App.instance.ttsManager.speak(speech, isPriority = false)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        // Reset state jika panggilan selesai / dibatalkan
        val pkg = sbn?.packageName ?: return
        if (pkg == WHATSAPP_PKG || pkg == WHATSAPP_BUSINESS_PKG) {
            val category = sbn.notification?.category ?: ""
            if (category == Notification.CATEGORY_CALL) {
                lastCallerName = ""
            }
        }
    }
}

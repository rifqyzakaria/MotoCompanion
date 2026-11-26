package com.motocompanion.service

import android.app.Notification
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.motocompanion.App
import com.motocompanion.R
import com.motocompanion.tts.SlangNormalizer
import com.motocompanion.util.ContactNameResolver
import com.motocompanion.util.RoadSyncBridge
import java.util.concurrent.Executors

/**
 * Listener Notifikasi Ringan & Bebas Konflik dengan Honda RoadSync:
 * 1. Tidak pernah memblokir Binder thread utama (seluruh pemrosesan dijalankan di background executor),
 *    dan TIDAK mengintervensi notifikasi Google Maps sehingga Honda RoadSync bebas meneruskan navigasi Maps
 *    & notifikasi ke Headunit motor tanpa hambatan.
 * 2. Mendeteksi seluruh format panggilan masuk WhatsApp (termasuk CallStyle Android 12–14) dan mencocokkan
 *    NAMA KONTAK asli dari buku telepon HP (bukan nomor telepon).
 * 3. Membacakan pesan WhatsApp secara cerdas: mengikuti Honda RoadSync bila Akses Notifikasi RoadSync aktif
 *    (atau dapat dibacakan langsung oleh H-Sync lengkap dengan resolusi Nama Kontak jika diaktifkan pengguna).
 */
class NotificationAnnouncerService : NotificationListenerService() {

    private val tag = "MotoNotificationService"
    private val workerExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    companion object {
        const val PREFS_NAME = "moto_companion_prefs"
        const val KEY_ANNOUNCE_CALLS = "announce_whatsapp_calls"
        const val KEY_ANNOUNCE_MESSAGES = "announce_whatsapp_messages"

        private const val WHATSAPP_PKG = "com.whatsapp"
        private const val WHATSAPP_BUSINESS_PKG = "com.whatsapp.w4b"

        @Volatile
        private var lastCallAnnouncementTime = 0L
        @Volatile
        private var lastCallerName = ""
        private const val CALL_REPEAT_INTERVAL_MS = 6000L

        @Volatile
        private var lastMessageKey = ""
        @Volatile
        private var lastMessageTime = 0L
        private const val MESSAGE_COOLDOWN_MS = 3000L
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val pkg = sbn.packageName ?: return

        // 1. Jika notifikasi berasal dari Honda RoadSync: picu start H-Sync HANYA SEKALI secara asinkron
        if (RoadSyncBridge.KNOWN_ROADSYNC_PACKAGES.contains(pkg)) {
            if (!RoadSyncBridge.isRoadSyncActive) {
                val appCtx = applicationContext
                mainHandler.post {
                    RoadSyncBridge.startAllServices(appCtx, "Notifikasi RoadSync ($pkg)")
                }
            }
            return
        }

        // 2. Keluar seketika (< 0.05ms) untuk semua aplikasi selain WhatsApp (termasuk Google Maps!)
        //    agar NotificationListenerService milik Honda RoadSync bekerja 100% mulus tanpa antrean Binder.
        if (pkg != WHATSAPP_PKG && pkg != WHATSAPP_BUSINESS_PKG) return

        // 3. Jalankan pemrosesan WhatsApp & pencarian Kontak HP di thread latar belakang terpisah
        workerExecutor.execute {
            try {
                processWhatsAppNotificationAsync(sbn)
            } catch (e: Exception) {
                Log.e(tag, "Error memproses notifikasi WhatsApp: ${e.message}")
            }
        }
    }

    private fun processWhatsAppNotificationAsync(sbn: StatusBarNotification) {
        if (!isConnectedToHeadunitOrIntercom()) {
            return
        }

        val notification = sbn.notification ?: return
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val announceCalls = prefs.getBoolean(KEY_ANNOUNCE_CALLS, true)
        val announceMessagesPref = prefs.getBoolean(KEY_ANNOUNCE_MESSAGES, false)

        // Cek apakah panggilan sudah diangkat / sedang berlangsung -> hentikan pengumuman suara segera
        if (isOngoingAnsweredCall(notification)) {
            clearActiveCallAlert()
            return
        }

        // Cek apakah ini Panggilan Masuk WhatsApp yang sedang berdering
        if (isIncomingRingingWhatsAppCall(notification)) {
            if (announceCalls) {
                handleIncomingWhatsAppCall(sbn)
            }
            return
        }

        // Untuk Pesan Chat Teks WhatsApp:
        // - Jika Akses Notifikasi Honda RoadSync sudah aktif DAN saklar manual H-Sync OFF, biarkan RoadSync yang menangani di Headunit.
        // - Jika pengguna menyalakan saklar baca pesan WA (atau jika Akses Notifikasi RoadSync belum aktif sehingga WA tidak masuk ke Headunit),
        //   H-Sync membacakannya lengkap dengan Nama Kontak asli!
        val roadSyncHandlesMessages = RoadSyncBridge.isRoadSyncInstalled(this) &&
                RoadSyncBridge.isRoadSyncNotificationAccessGranted(this)
        val shouldReadMessage = announceMessagesPref || !roadSyncHandlesMessages

        if (shouldReadMessage && !isSummaryOrSystemWaNotification(notification)) {
            handleIncomingWhatsAppMessage(sbn)
        }
    }

    private fun isConnectedToHeadunitOrIntercom(): Boolean {
        if (RoadSyncBridge.isAndroidAutoConnected(this)) return false
        if (RoadSyncBridge.isRoadSyncActive || RoadSyncBridge.isCompanionActive(this)) return true

        val hasRoadSyncNotification = try {
            activeNotifications?.any { active ->
                RoadSyncBridge.KNOWN_ROADSYNC_PACKAGES.contains(active.packageName)
            } ?: false
        } catch (_: Exception) {
            false
        }
        if (hasRoadSyncNotification) {
            RoadSyncBridge.isRoadSyncActive = true
            return true
        }

        if (RoadSyncBridge.getConnectedDeviceName(this) != null) return true
        return RoadSyncBridge.isBluetoothAudioRouteConnected(this)
    }

    /**
     * Mendeteksi seluruh variasi Panggilan Masuk WhatsApp (Suara / Video / CallStyle Android 12–14)
     * tanpa terblokir saat array actions atau fullScreenIntent disembunyikan sistem.
     */
    private fun isIncomingRingingWhatsAppCall(notification: Notification): Boolean {
        val extras = notification.extras ?: return false
        val category = notification.category ?: ""

        val callType = extras.getInt("android.callType", -1)
        if (callType == 2 || callType == 3) return false

        val usesChronometer = extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER, false)
        if (usesChronometer) return false

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString() ?: ""
        val combined = "$title $text $subText".lowercase()

        // Abaikan panggilan tak terjawab, panggilan keluar, atau panggilan yang sedang berlangsung
        if (combined.contains("tak terjawab") ||
            combined.contains("missed") ||
            combined.contains("berlangsung") ||
            combined.contains("ongoing") ||
            combined.contains("memanggil") ||
            combined.contains("calling") ||
            combined.contains("berdering...")
        ) {
            return false
        }

        // Semua notifikasi panggilan berdering WhatsApp memiliki CATEGORY_CALL atau CallStyle incoming (callType == 1)
        if (category == Notification.CATEGORY_CALL || callType == 1) {
            return true
        }

        val template = extras.getString(Notification.EXTRA_TEMPLATE) ?: ""
        if (template.contains("CallStyle", ignoreCase = true)) {
            return true
        }

        return combined.contains("panggilan suara") ||
                combined.contains("panggilan video") ||
                combined.contains("panggilan grup") ||
                combined.contains("panggilan masuk") ||
                combined.contains("incoming voice call") ||
                combined.contains("incoming video call") ||
                combined.contains("incoming group call") ||
                combined.contains("incoming call") ||
                combined.contains("whatsapp voice call") ||
                combined.contains("whatsapp video call")
    }

    private fun isOngoingAnsweredCall(notification: Notification): Boolean {
        val extras = notification.extras ?: return false
        val callType = extras.getInt("android.callType", -1)
        val usesChronometer = extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER, false)
        return notification.category == Notification.CATEGORY_CALL && (callType == 2 || usesChronometer)
    }

    private fun isSummaryOrSystemWaNotification(notification: Notification): Boolean {
        if ((notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return true
        if (notification.category == Notification.CATEGORY_CALL ||
            notification.category == Notification.CATEGORY_SERVICE ||
            notification.category == Notification.CATEGORY_PROGRESS
        ) {
            return true
        }
        val extras = notification.extras ?: return true
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim() ?: ""
        if (text.isBlank()) return true
        val lower = text.lowercase()
        return lower.contains("memeriksa pesan baru") ||
                lower.contains("checking for new messages") ||
                lower.contains("pencadangan") ||
                lower.contains("backup") ||
                lower.contains("whatsapp web") ||
                Regex("^\\d+\\s+(pesan|messages).*").matches(lower)
    }

    private fun handleIncomingWhatsAppCall(sbn: StatusBarNotification) {
        val now = System.currentTimeMillis()
        val callerName = ContactNameResolver.resolveWhatsAppCallerName(this, sbn)

        MediaSessionService.setIncomingWhatsAppCaller(callerName)

        if (callerName == lastCallerName && (now - lastCallAnnouncementTime) < CALL_REPEAT_INTERVAL_MS) {
            return
        }

        lastCallerName = callerName
        lastCallAnnouncementTime = now

        val speech = getString(R.string.tts_incoming_call, callerName)
        Log.i(tag, "📞 Mengumumkan nama kontak panggilan WA: $speech")

        mainHandler.post {
            App.instance.ttsManager.speak(speech, isPriority = true)
        }
    }

    private fun handleIncomingWhatsAppMessage(sbn: StatusBarNotification) {
        val extras = sbn.notification?.extras ?: return
        val rawMessage = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim() ?: return
        if (rawMessage.isBlank()) return

        val senderName = ContactNameResolver.resolveWhatsAppCallerName(this, sbn)
        val dedupKey = "$senderName|$rawMessage"
        val now = System.currentTimeMillis()

        if (dedupKey == lastMessageKey && (now - lastMessageTime) < MESSAGE_COOLDOWN_MS) {
            return
        }

        lastMessageKey = dedupKey
        lastMessageTime = now

        val cleanBody = SlangNormalizer.normalize(rawMessage)
        if (cleanBody.isBlank()) return

        val speech = getString(R.string.tts_incoming_message, senderName, cleanBody)
        Log.i(tag, "💬 Membacakan pesan WA dari kontak: $speech")

        mainHandler.post {
            App.instance.ttsManager.speak(speech, isPriority = false)
        }
    }

    private fun clearActiveCallAlert() {
        if (lastCallerName.isNotEmpty() || MediaSessionService.activeIncomingCallerName != null) {
            lastCallerName = ""
            MediaSessionService.setIncomingWhatsAppCaller(null)
            mainHandler.post {
                App.instance.ttsManager.stop()
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        val pkg = sbn?.packageName ?: return

        if (RoadSyncBridge.KNOWN_ROADSYNC_PACKAGES.contains(pkg)) {
            workerExecutor.execute {
                val stillPresent = try {
                    activeNotifications?.any { RoadSyncBridge.KNOWN_ROADSYNC_PACKAGES.contains(it.packageName) } ?: false
                } catch (_: Exception) {
                    false
                }
                if (!stillPresent && RoadSyncBridge.getConnectedDeviceName(this) == null) {
                    mainHandler.post {
                        RoadSyncBridge.stopAllServices(applicationContext, "Notifikasi RoadSync ditutup")
                    }
                }
            }
            return
        }

        if (pkg == WHATSAPP_PKG || pkg == WHATSAPP_BUSINESS_PKG) {
            val category = sbn.notification?.category ?: ""
            if (category == Notification.CATEGORY_CALL || sbn.notification?.fullScreenIntent != null) {
                clearActiveCallAlert()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        workerExecutor.shutdownNow()
    }
}

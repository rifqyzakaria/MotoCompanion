package com.motocompanion.service

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat
import com.motocompanion.App
import com.motocompanion.R
import com.motocompanion.util.ContactNameResolver
import com.motocompanion.util.RoadSyncBridge

/**
 * Listener Notifikasi Cerdas H-Sync:
 * 1. Pembacaan pesan chat teks WhatsApp diserahkan mengikuti aplikasi bawaan Honda RoadSync (tanpa suara ganda).
 * 2. Khusus untuk Panggilan Masuk WhatsApp saat terhubung ke Headunit motor: mengekstrak & mencocokkan
 *    NAMA KONTAK asli dari buku telepon HP (bukan nomor telepon mentah) untuk diumumkan ke intercom & ditampilkan.
 * 3. Memantau navigasi aktif Google Maps / RoadSync untuk menjaga GPS tetap pada mode Turbo Realtime (anti telat Turn-by-Turn).
 */
class NotificationAnnouncerService : NotificationListenerService() {

    private val tag = "MotoNotificationService"

    companion object {
        const val PREFS_NAME = "moto_companion_prefs"
        const val KEY_ANNOUNCE_CALLS = "announce_whatsapp_calls"

        private const val WHATSAPP_PKG = "com.whatsapp"
        private const val WHATSAPP_BUSINESS_PKG = "com.whatsapp.w4b"
        private const val CALL_BANNER_NOTIFICATION_ID = 2004

        private var lastCallAnnouncementTime = 0L
        private var lastCallerName = ""
        private const val CALL_REPEAT_INTERVAL_MS = 6500L // Ulangi sapaan nama kontak dengan jeda nyaman selama masih berdering
        private var lastMapsBoostTime = 0L
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return

        val pkg = sbn.packageName ?: return

        // Jika Android Auto mobil aktif, jangan jalankan apapun
        if (RoadSyncBridge.isAndroidAutoConnected(this)) return

        // 1. Deteksi saat Honda RoadSync dibuka / terhubung ke Headunit -> Auto-Start H-Sync (Volume 20% + GPS Realtime)
        if (RoadSyncBridge.KNOWN_ROADSYNC_PACKAGES.contains(pkg)) {
            RoadSyncBridge.startAllServices(this, "Notifikasi RoadSync ($pkg)")
            return
        }

        // 2. Deteksi navigasi aktif Google Maps saat terhubung ke Headunit -> Pastikan GPS pada Turbo Realtime Lock (0ms delay)
        if (pkg == RoadSyncBridge.PKG_GOOGLE_MAPS) {
            val category = sbn.notification?.category ?: ""
            if ((category == Notification.CATEGORY_NAVIGATION || sbn.isOngoing) && isConnectedToHeadunit()) {
                val now = System.currentTimeMillis()
                if (now - lastMapsBoostTime > 15_000L) {
                    lastMapsBoostTime = now
                    if (RoadSyncBridge.isGpsRealtimeEnabled(this)) {
                        RoadSyncBridge.startGpsService(this, boostNavigation = true)
                    }
                }
            }
            return
        }

        // 3. Hanya tangani Panggilan Masuk dari WhatsApp / WhatsApp Business
        //    (Pesan chat teks WhatsApp dibiarkan mengikuti aplikasi Honda RoadSync sepenuhnya)
        if (pkg != WHATSAPP_PKG && pkg != WHATSAPP_BUSINESS_PKG) return

        // 4. Wajib hanya aktif pada saat aplikasi terkoneksi ke Headunit motor / RoadSync
        if (!isConnectedToHeadunit()) {
            return
        }

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val announceCalls = prefs.getBoolean(KEY_ANNOUNCE_CALLS, true)
        if (!announceCalls) return

        val notification = sbn.notification ?: return
        if (isIncomingRingingWhatsAppCall(notification)) {
            handleIncomingWhatsAppCall(sbn)
        } else if (isOngoingOrEndedCall(notification)) {
            // Jika panggilan sudah diangkat (ongoing) atau selesai, hentikan pengumuman suara segera
            clearActiveCallAlert()
        }
    }

    /**
     * Memeriksa apakah HP sedang terhubung ke Headunit motor / Honda RoadSync
     */
    private fun isConnectedToHeadunit(): Boolean {
        if (RoadSyncBridge.isAndroidAutoConnected(this)) return false
        if (RoadSyncBridge.isRoadSyncActive) return true

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

        if (RoadSyncBridge.getConnectedDeviceName(this) != null) {
            return true
        }

        return RoadSyncBridge.isCompanionActive(this)
    }

    /**
     * Mendeteksi secara presisi apakah notifikasi WhatsApp adalah PANGGILAN MASUK YANG SEDANG BERDERING
     * (Bukan pesan chat biasa, bukan panggilan keluar, bukan panggilan yang sudah diangkat/berlangsung, dan bukan panggilan tak terjawab).
     */
    private fun isIncomingRingingWhatsAppCall(notification: Notification): Boolean {
        val extras = notification.extras ?: return false
        val category = notification.category ?: ""

        // Cek CallStyle Android 12+ (1 = CALL_TYPE_INCOMING, 2 = ONGOING, 3 = SCREENING)
        val callType = extras.getInt("android.callType", -1)
        if (callType == 2 || callType == 3) {
            return false
        }

        // Jika chronometer aktif (00:01, 00:02...), berarti panggilan sudah dijawab / sedang berlangsung
        val usesChronometer = extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER, false)
        if (usesChronometer) {
            return false
        }

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val combined = "$title $text".lowercase()

        // Abaikan panggilan tak terjawab, panggilan keluar, atau panggilan sedang berlangsung
        if (combined.contains("tak terjawab") ||
            combined.contains("missed") ||
            combined.contains("berlangsung") ||
            combined.contains("ongoing") ||
            combined.contains("memanggil") ||
            combined.contains("calling") ||
            combined.contains("menghubungkan")
        ) {
            return false
        }

        if (callType == 1) return true

        val hasIncomingKeywords = combined.contains("panggilan suara masuk") ||
                combined.contains("panggilan video masuk") ||
                combined.contains("panggilan grup masuk") ||
                combined.contains("panggilan masuk") ||
                combined.contains("incoming voice call") ||
                combined.contains("incoming video call") ||
                combined.contains("incoming group call") ||
                combined.contains("incoming call")

        if (hasIncomingKeywords) return true

        // Fallback untuk CallStyle / FullScreenIntent berlabel "Panggilan suara WhatsApp" saat berdering (memiliki tombol Jawab & Tolak)
        if (category == Notification.CATEGORY_CALL && notification.fullScreenIntent != null) {
            val actionCount = notification.actions?.size ?: 0
            if (actionCount >= 2) {
                return true
            }
        }

        return false
    }

    private fun isOngoingOrEndedCall(notification: Notification): Boolean {
        val extras = notification.extras ?: return false
        val callType = extras.getInt("android.callType", -1)
        val usesChronometer = extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER, false)
        return notification.category == Notification.CATEGORY_CALL && (callType == 2 || usesChronometer)
    }

    private fun handleIncomingWhatsAppCall(sbn: StatusBarNotification) {
        val now = System.currentTimeMillis()

        // Resolusi NAMA KONTAK asli dari buku telepon HP (tidak mengeja nomor telepon)
        val callerName = ContactNameResolver.resolveWhatsAppCallerName(this, sbn)

        // Perbarui tampilan metadata sementara di TFT & banner notifikasi dengan Nama Kontak
        MediaSessionService.setIncomingWhatsAppCaller(callerName)
        postCallerNameBanner(callerName)

        // Cegah pengulangan suara beruntun saat notifikasi berdering diperbarui tiap detik oleh WhatsApp
        if (callerName == lastCallerName && (now - lastCallAnnouncementTime) < CALL_REPEAT_INTERVAL_MS) {
            return
        }

        lastCallerName = callerName
        lastCallAnnouncementTime = now

        val speech = getString(R.string.tts_incoming_call, callerName)
        Log.i(tag, "📞 Mengumumkan nama kontak panggilan WA: $speech")

        App.instance.ttsManager.speak(speech, isPriority = true)
    }

    private fun postCallerNameBanner(callerName: String) {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val notif = NotificationCompat.Builder(this, App.CHANNEL_TTS_ALERTS)
                .setSmallIcon(R.drawable.ic_bike)
                .setContentTitle(getString(R.string.wa_call_banner_title))
                .setContentText(callerName)
                .setCategory(Notification.CATEGORY_CALL)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .setSilent(true)
                .setAutoCancel(true)
                .build()
            nm.notify(CALL_BANNER_NOTIFICATION_ID, notif)
        } catch (e: Exception) {
            Log.w(tag, "Gagal menampilkan banner nama pemanggil WA: ${e.message}")
        }
    }

    private fun clearActiveCallAlert() {
        if (lastCallerName.isNotEmpty() || MediaSessionService.activeIncomingCallerName != null) {
            lastCallerName = ""
            MediaSessionService.setIncomingWhatsAppCaller(null)
            App.instance.ttsManager.stop()
            try {
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.cancel(CALL_BANNER_NOTIFICATION_ID)
            } catch (_: Exception) {}
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        val pkg = sbn?.packageName ?: return

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

        if (pkg == WHATSAPP_PKG || pkg == WHATSAPP_BUSINESS_PKG) {
            val category = sbn.notification?.category ?: ""
            if (category == Notification.CATEGORY_CALL || sbn.notification?.fullScreenIntent != null) {
                clearActiveCallAlert()
            }
        }
    }
}

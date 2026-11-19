package com.motocompanion.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.SystemClock
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import com.motocompanion.R
import com.motocompanion.model.InstalledPlayer
import com.motocompanion.service.MediaSessionService

object MediaControlDispatcher {

    private const val PREFS_NAME = "moto_player_prefs"
    const val KEY_SELECTED_PACKAGE = "selected_player_package"
    const val KEY_SELECTED_NAME = "selected_player_name"
    const val INTERNAL_PACKAGE_ID = "com.motocompanion.internal"

    fun getSelectedPackage(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_SELECTED_PACKAGE, INTERNAL_PACKAGE_ID) ?: INTERNAL_PACKAGE_ID
    }

    fun getSelectedName(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_SELECTED_NAME, "Pemutar Mandiri MotoCompanion") ?: "Pemutar Mandiri MotoCompanion"
    }

    fun setSelectedPlayer(context: Context, packageName: String, appName: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_SELECTED_PACKAGE, packageName)
            .putString(KEY_SELECTED_NAME, appName)
            .apply()
    }

    /**
     * Mendapatkan daftar semua pemutar musik & video yang terinstal di HP
     * (dari Play Store maupun file APK luar/pihak ketiga)
     */
    fun getInstalledMediaPlayers(context: Context, includeAllApps: Boolean = false): List<InstalledPlayer> {
        val pm = context.packageManager
        val playersMap = mutableMapOf<String, InstalledPlayer>()

        // 1. Tambahkan opsi Pemutar Bawaan MotoCompanion di paling atas
        playersMap[INTERNAL_PACKAGE_ID] = InstalledPlayer(
            appName = "Pemutar Mandiri MotoCompanion (Bawaan)",
            packageName = INTERNAL_PACKAGE_ID,
            icon = ContextCompat.getDrawable(context, R.drawable.ic_bike),
            isInternal = true
        )

        // 2. Deteksi aplikasi pemutar Audio (MIME audio/*)
        val audioIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse("content://dummy.mp3"), "audio/*")
        }
        val audioApps = pm.queryIntentActivities(audioIntent, PackageManager.MATCH_DEFAULT_ONLY)
        for (info in audioApps) {
            val pkg = info.activityInfo.packageName
            if (pkg != context.packageName && !playersMap.containsKey(pkg)) {
                val name = info.loadLabel(pm).toString()
                val icon = info.loadIcon(pm)
                playersMap[pkg] = InstalledPlayer(name, pkg, icon, isInternal = false, isVideoPlayer = false)
            }
        }

        // 3. Deteksi aplikasi pemutar Video (MIME video/*)
        val videoIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse("content://dummy.mp4"), "video/*")
        }
        val videoApps = pm.queryIntentActivities(videoIntent, PackageManager.MATCH_DEFAULT_ONLY)
        for (info in videoApps) {
            val pkg = info.activityInfo.packageName
            if (pkg != context.packageName) {
                val existing = playersMap[pkg]
                if (existing != null) {
                    playersMap[pkg] = existing.copy(isVideoPlayer = true)
                } else {
                    val name = info.loadLabel(pm).toString()
                    val icon = info.loadIcon(pm)
                    playersMap[pkg] = InstalledPlayer(name, pkg, icon, isInternal = false, isVideoPlayer = true)
                }
            }
        }

        // 4. Deteksi aplikasi yang menerima event Tombol Media (MediaButtonReceiver)
        val mediaButtonIntent = Intent(Intent.ACTION_MEDIA_BUTTON)
        val mediaButtonReceivers = pm.queryBroadcastReceivers(mediaButtonIntent, 0)
        for (info in mediaButtonReceivers) {
            val pkg = info.activityInfo.packageName
            if (pkg != context.packageName && !playersMap.containsKey(pkg)) {
                val name = info.activityInfo.applicationInfo.loadLabel(pm).toString()
                val icon = info.activityInfo.applicationInfo.loadIcon(pm)
                playersMap[pkg] = InstalledPlayer(name, pkg, icon, isInternal = false, isVideoPlayer = false)
            }
        }

        // 5. Jika pengguna ingin menampilkan SEMUA aplikasi yang terpasang (untuk APK kustom langka)
        if (includeAllApps) {
            val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val launchableApps = pm.queryIntentActivities(mainIntent, 0)
            for (info in launchableApps) {
                val pkg = info.activityInfo.packageName
                if (pkg != context.packageName && !playersMap.containsKey(pkg)) {
                    val name = info.loadLabel(pm).toString()
                    val icon = info.loadIcon(pm)
                    playersMap[pkg] = InstalledPlayer(name, pkg, icon, isInternal = false, isVideoPlayer = false)
                }
            }
        }

        return playersMap.values.toList()
    }

    /**
     * Meneruskan perintah kontrol tombol stang / intercom ke pemutar media yang dipilih
     */
    fun dispatchMediaAction(context: Context, keyCode: Int) {
        val selectedPkg = getSelectedPackage(context)

        // Jika memilih pemutar internal MotoCompanion
        if (selectedPkg == INTERNAL_PACKAGE_ID) {
            val serviceIntent = Intent(context, MediaSessionService::class.java)
            when (keyCode) {
                KeyEvent.KEYCODE_MEDIA_PLAY -> serviceIntent.action = MediaSessionService.ACTION_PLAY
                KeyEvent.KEYCODE_MEDIA_PAUSE -> serviceIntent.action = MediaSessionService.ACTION_PAUSE
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                KeyEvent.KEYCODE_HEADSETHOOK -> serviceIntent.action = MediaSessionService.ACTION_TOGGLE
                KeyEvent.KEYCODE_MEDIA_NEXT -> serviceIntent.action = MediaSessionService.ACTION_NEXT
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> serviceIntent.action = MediaSessionService.ACTION_PREV
            }
            context.startService(serviceIntent)
            return
        }

        // Jika memilih aplikasi pihak ketiga (Play Store / Sideload luar Play Store)
        val eventTime = SystemClock.uptimeMillis()
        val downEvent = KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, keyCode, 0)
        val upEvent = KeyEvent(eventTime, eventTime, KeyEvent.ACTION_UP, keyCode, 0)

        // 1. Kirim MediaButton Intent langsung tertuju ke package aplikasi terpilih
        val downIntent = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
            setPackage(selectedPkg)
            putExtra(Intent.EXTRA_KEY_EVENT, downEvent)
        }
        val upIntent = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
            setPackage(selectedPkg)
            putExtra(Intent.EXTRA_KEY_EVENT, upEvent)
        }

        context.sendOrderedBroadcast(downIntent, null)
        context.sendOrderedBroadcast(upIntent, null)

        // 2. Sebagai penguat: gunakan AudioManager dispatch
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.dispatchMediaKeyEvent(downEvent)
        audioManager.dispatchMediaKeyEvent(upEvent)
    }

    /**
     * Membuka aplikasi pemutar media yang sedang dipilih
     */
    fun launchSelectedPlayer(context: Context) {
        val selectedPkg = getSelectedPackage(context)
        if (selectedPkg == INTERNAL_PACKAGE_ID) {
            val intent = Intent(context, com.motocompanion.ui.MusicActivity::class.java)
            context.startActivity(intent)
            return
        }

        val launchIntent = context.packageManager.getLaunchIntentForPackage(selectedPkg)
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launchIntent)
        }
    }
}

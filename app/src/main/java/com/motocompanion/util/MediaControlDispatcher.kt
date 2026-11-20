package com.motocompanion.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import com.motocompanion.R
import com.motocompanion.model.InstalledPlayer
import com.motocompanion.service.MediaSessionService
import com.motocompanion.service.NotificationAnnouncerService

object MediaControlDispatcher {

    private const val TAG = "MediaControlDispatcher"
    private const val PREFS_NAME = "moto_player_prefs"
    const val KEY_SELECTED_PACKAGE = "selected_player_package"
    const val KEY_SELECTED_NAME = "selected_player_name"
    const val INTERNAL_PACKAGE_ID = "com.motocompanion.internal"

    data class TrackInfo(
        val title: String,
        val artist: String,
        val isPlaying: Boolean,
        val appName: String,
        val packageName: String,
        val durationMs: Long = 0L,
        val positionMs: Long = 0L
    )

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
     * Mengambil MediaController aktif dari sistem menggunakan MediaSessionManager.
     * Mengutamakan package aplikasi yang sedang dipilih.
     */
    fun getActiveMediaController(context: Context, targetPackage: String? = null): MediaController? {
        val pkg = targetPackage ?: getSelectedPackage(context)
        if (pkg == INTERNAL_PACKAGE_ID) return null

        try {
            val mediaSessionManager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
                ?: return null
            val componentName = ComponentName(context, NotificationAnnouncerService::class.java)
            val activeSessions = mediaSessionManager.getActiveSessions(componentName)

            // 1. Cari session yang package-nya persis cocok dengan target (hindari package MotoCompanion sendiri)
            for (controller in activeSessions) {
                if (controller.packageName == context.packageName) continue
                if (controller.packageName.equals(pkg, ignoreCase = true)) {
                    return controller
                }
            }

            // 2. Jika tidak ditemukan yang cocok persis dengan pkg, cari session yang sedang memutar (PLAYING)
            for (controller in activeSessions) {
                if (controller.packageName == context.packageName) continue
                val state = controller.playbackState?.state
                if (state == PlaybackState.STATE_PLAYING) {
                    return controller
                }
            }

            // 3. Fallback: ambil session pertama yang bukan package MotoCompanion sendiri
            for (controller in activeSessions) {
                if (controller.packageName != context.packageName) {
                    return controller
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Izin Notification Listener belum diberikan, tidak dapat mengakses getActiveSessions: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Eror saat mengambil active sessions: ${e.message}")
        }
        return null
    }

    /**
     * Mengambil data lagu & status pemutaran yang sedang aktif
     */
    fun getActiveTrackInfo(context: Context): TrackInfo? {
        val selectedPkg = getSelectedPackage(context)
        val selectedName = getSelectedName(context)

        // Kasus 1: Pemutar Internal
        if (selectedPkg == INTERNAL_PACKAGE_ID) {
            val song = MediaSessionService.currentSong
            val isPlaying = MediaSessionService.isPlaying
            return if (song != null) {
                TrackInfo(
                    title = song.title,
                    artist = song.artist,
                    isPlaying = isPlaying,
                    appName = "MotoCompanion (Bawaan)",
                    packageName = INTERNAL_PACKAGE_ID,
                    durationMs = song.durationMs,
                    positionMs = 0L
                )
            } else {
                TrackInfo(
                    title = "Tidak ada lagu yang dipilih",
                    artist = "Buka menu Lagu Offline",
                    isPlaying = false,
                    appName = "MotoCompanion (Bawaan)",
                    packageName = INTERNAL_PACKAGE_ID,
                    durationMs = 180000L,
                    positionMs = 0L
                )
            }
        }

        // Kasus 2: Aplikasi Pihak Ketiga (BitChord, Spotify, YouTube Music, VLC, dll.)
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val isHardwareAudioActive = audioManager?.isMusicActive == true

        val controller = getActiveMediaController(context, selectedPkg) ?: getActiveMediaController(context, null)
        if (controller != null) {
            val metadata = controller.metadata
            val state = controller.playbackState
            val isPlaying = (state?.state == PlaybackState.STATE_PLAYING) || isHardwareAudioActive

            val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
                ?: metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
                ?: if (isHardwareAudioActive) "Memutar $selectedName" else "Pemutar Aktif"
            val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                ?: metadata?.getString(MediaMetadata.METADATA_KEY_AUTHOR)
                ?: selectedName

            val duration = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
            val position = state?.position ?: 0L

            return TrackInfo(
                title = title,
                artist = artist,
                isPlaying = isPlaying,
                appName = selectedName,
                packageName = controller.packageName,
                durationMs = if (duration > 0) duration else 180000L,
                positionMs = position
            )
        }

        return TrackInfo(
            title = if (isHardwareAudioActive) "Memutar $selectedName" else "Putar $selectedName",
            artist = "MotoCompanion PCX",
            isPlaying = isHardwareAudioActive,
            appName = selectedName,
            packageName = selectedPkg,
            durationMs = 180000L,
            positionMs = 0L
        )
    }

    /**
     * Meneruskan perintah kontrol tombol stang / intercom / layar ke pemutar media yang dipilih.
     * Menggunakan strategi multi-tier:
     * Tier 1: MediaSessionManager -> MediaController TransportControls (Standard modern Android)
     * Tier 2: Explicit Component Targeting (Manifest Broadcast Receiver / Service)
     * Tier 3: Auto-Wake / Launch jika pemutar belum dibuka
     * Tier 4: Global AudioManager Media Key Event Dispatch & Ordered Broadcast
     */
    fun dispatchMediaAction(context: Context, keyCode: Int, autoWakeIfDormant: Boolean = true): Boolean {
        val selectedPkg = getSelectedPackage(context)
        Log.i(TAG, "Dispatching media action keyCode=$keyCode to $selectedPkg")

        // === 1. Jika Memilih Pemutar Internal MotoCompanion ===
        if (selectedPkg == INTERNAL_PACKAGE_ID) {
            val serviceIntent = Intent(context, MediaSessionService::class.java)
            when (keyCode) {
                KeyEvent.KEYCODE_MEDIA_PLAY -> serviceIntent.action = MediaSessionService.ACTION_PLAY
                KeyEvent.KEYCODE_MEDIA_PAUSE -> serviceIntent.action = MediaSessionService.ACTION_PAUSE
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                KeyEvent.KEYCODE_HEADSETHOOK -> serviceIntent.action = MediaSessionService.ACTION_TOGGLE
                KeyEvent.KEYCODE_MEDIA_NEXT -> serviceIntent.action = MediaSessionService.ACTION_NEXT
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> serviceIntent.action = MediaSessionService.ACTION_PREV
                KeyEvent.KEYCODE_MEDIA_STOP -> serviceIntent.action = MediaSessionService.ACTION_PAUSE
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ContextCompat.startForegroundService(context, serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Gagal memulai MediaSessionService: ${e.message}")
            }
            return true
        }

        // Siapkan KeyEvent Down & Up
        val eventTime = SystemClock.uptimeMillis()
        val downEvent = KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, keyCode, 0)
        val upEvent = KeyEvent(eventTime, eventTime, KeyEvent.ACTION_UP, keyCode, 0)

        // === 2. TIER 1: Kontrol Langsung via MediaController TransportControls ===
        val controller = getActiveMediaController(context, selectedPkg)
        if (controller != null) {
            try {
                val transport = controller.transportControls
                when (keyCode) {
                    KeyEvent.KEYCODE_MEDIA_PLAY -> transport.play()
                    KeyEvent.KEYCODE_MEDIA_PAUSE -> transport.pause()
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                    KeyEvent.KEYCODE_HEADSETHOOK -> {
                        val state = controller.playbackState?.state
                        if (state == PlaybackState.STATE_PLAYING) {
                            transport.pause()
                        } else {
                            transport.play()
                        }
                    }
                    KeyEvent.KEYCODE_MEDIA_NEXT -> transport.skipToNext()
                    KeyEvent.KEYCODE_MEDIA_PREVIOUS -> transport.skipToPrevious()
                    KeyEvent.KEYCODE_MEDIA_STOP -> transport.stop()
                    else -> {
                        controller.dispatchMediaButtonEvent(downEvent)
                        controller.dispatchMediaButtonEvent(upEvent)
                    }
                }
                // Kirim juga event key langsung ke controller sebagai penguat
                controller.dispatchMediaButtonEvent(downEvent)
                controller.dispatchMediaButtonEvent(upEvent)
                Log.i(TAG, "Berhasil mengontrol $selectedPkg via MediaController TransportControls")
                return true
            } catch (e: Exception) {
                Log.w(TAG, "TransportControls gagal, beralih ke strategi berikutnya: ${e.message}")
            }
        }

        // === 3. TIER 2: Pengiriman Intent Eksplisit ke ComponentReceiver / ComponentService ===
        val pm = context.packageManager
        var explicitHandled = false

        // 3a. Cari BroadcastReceiver di aplikasi target yang merespons ACTION_MEDIA_BUTTON
        try {
            val mediaButtonIntent = Intent(Intent.ACTION_MEDIA_BUTTON).setPackage(selectedPkg)
            val receivers = pm.queryBroadcastReceivers(mediaButtonIntent, 0)
            for (resolveInfo in receivers) {
                val comp = ComponentName(selectedPkg, resolveInfo.activityInfo.name)
                val explicitDown = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
                    component = comp
                    putExtra(Intent.EXTRA_KEY_EVENT, downEvent)
                }
                val explicitUp = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
                    component = comp
                    putExtra(Intent.EXTRA_KEY_EVENT, upEvent)
                }
                context.sendBroadcast(explicitDown)
                context.sendBroadcast(explicitUp)
                explicitHandled = true
                Log.i(TAG, "Mengirim explicit broadcast ke component $comp")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Explicit broadcast gagal: ${e.message}")
        }

        // === 4. TIER 3: Bangunkan Aplikasi jika sedang tertidur dan diperintahkan Play ===
        val isPlayAction = (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY ||
                keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ||
                keyCode == KeyEvent.KEYCODE_HEADSETHOOK)

        if (autoWakeIfDormant && isPlayAction && controller == null) {
            try {
                val launchIntent = pm.getLaunchIntentForPackage(selectedPkg)
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                    context.startActivity(launchIntent)
                    Log.i(TAG, "Membangunkan aplikasi $selectedPkg ke latar depan untuk memulai musik")

                    // Berikan jeda sejenak agar app terbuka, lalu kirim play
                    Handler(Looper.getMainLooper()).postDelayed({
                        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                        val wakeDown = KeyEvent(SystemClock.uptimeMillis(), SystemClock.uptimeMillis(), KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY, 0)
                        val wakeUp = KeyEvent(SystemClock.uptimeMillis(), SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY, 0)
                        audioManager.dispatchMediaKeyEvent(wakeDown)
                        audioManager.dispatchMediaKeyEvent(wakeUp)
                    }, 700)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Gagal membangunkan aplikasi: ${e.message}")
            }
        }

        // === 5. TIER 4: Global AudioManager Media Key Event Dispatch & Ordered Broadcast ===
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.dispatchMediaKeyEvent(downEvent)
            audioManager.dispatchMediaKeyEvent(upEvent)

            val downBroadcast = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
                setPackage(selectedPkg)
                putExtra(Intent.EXTRA_KEY_EVENT, downEvent)
            }
            val upBroadcast = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
                setPackage(selectedPkg)
                putExtra(Intent.EXTRA_KEY_EVENT, upEvent)
            }
            context.sendOrderedBroadcast(downBroadcast, null)
            context.sendOrderedBroadcast(upBroadcast, null)
        } catch (e: Exception) {
            Log.e(TAG, "Gagal dispatch AudioManager key event: ${e.message}")
        }

        return true
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

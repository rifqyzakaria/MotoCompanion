package com.motocompanion.service

import android.app.Notification
import android.app.PendingIntent
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import androidx.media.MediaBrowserServiceCompat
import androidx.media.session.MediaButtonReceiver
import com.motocompanion.App
import com.motocompanion.R
import com.motocompanion.model.Song
import com.motocompanion.ui.MainActivity
import com.motocompanion.ui.MusicActivity
import com.motocompanion.util.MediaControlDispatcher

open class MediaSessionService : MediaBrowserServiceCompat() {

    private val tag = "MotoMediaSession"

    companion object {
        const val MEDIA_ROOT_ID = "root_motocompanion"
        const val MEDIA_ITEM_STREAM_ID = "motocompanion_stream_player"

        const val ACTION_PLAY = "com.motocompanion.ACTION_PLAY"
        const val ACTION_PAUSE = "com.motocompanion.ACTION_PAUSE"
        const val ACTION_NEXT = "com.motocompanion.ACTION_NEXT"
        const val ACTION_PREV = "com.motocompanion.ACTION_PREV"
        const val ACTION_TOGGLE = "com.motocompanion.ACTION_TOGGLE"
        const val ACTION_START_FOREGROUND = "com.motocompanion.ACTION_START_FOREGROUND"
        const val ACTION_STOP_SERVICE = "com.motocompanion.ACTION_STOP_SERVICE"

        const val BROADCAST_PLAYLIST_UPDATED = "com.motocompanion.PLAYLIST_UPDATED"
        const val BROADCAST_TRACK_CHANGED = "com.motocompanion.TRACK_CHANGED"

        var currentSong: Song? = null
            private set
        var isPlaying: Boolean = false
            private set

        val playlist = mutableListOf<Song>()
        var currentIndex = 0

        /**
         * Singleton MediaSessionCompat agar semua alias service (SpotifyMediaBrowserService & MediaSessionService)
         * berbagi instance session token yang sama persis untuk Honda RoadSync dan meter TFT PCX.
         */
        @Volatile
        var sharedMediaSession: MediaSessionCompat? = null
    }

    private lateinit var mediaSession: MediaSessionCompat
    private var mediaPlayer: MediaPlayer? = null

    private val syncHandler = Handler(Looper.getMainLooper())
    private var lastSyncedTitle = ""
    private var lastSyncedArtist = ""
    private var lastSyncedState = -1

    private val syncRunnable = object : Runnable {
        override fun run() {
            val selectedPkg = MediaControlDispatcher.getSelectedPackage(this@MediaSessionService)
            if (selectedPkg != MediaControlDispatcher.INTERNAL_PACKAGE_ID) {
                syncExternalPlayer()
            }
            syncHandler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate() {
        super.onCreate()

        initMediaSession()
        loadLocalSongs()

        // Mulai background sync loop setiap 1 detik agar RoadSync & PCX TFT selalu menerima lagu terkini
        syncHandler.post(syncRunnable)
    }

    private fun getSupportedActions(): Long {
        return PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_PLAY_PAUSE or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                PlaybackStateCompat.ACTION_STOP or
                PlaybackStateCompat.ACTION_PREPARE or
                PlaybackStateCompat.ACTION_PREPARE_FROM_MEDIA_ID or
                PlaybackStateCompat.ACTION_PREPARE_FROM_SEARCH or
                PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID or
                PlaybackStateCompat.ACTION_PLAY_FROM_SEARCH or
                PlaybackStateCompat.ACTION_SEEK_TO
    }

    private fun initMediaSession() {
        if (sharedMediaSession != null) {
            mediaSession = sharedMediaSession!!
            sessionToken = mediaSession.sessionToken
            Log.i(tag, "Menggunakan sharedMediaSession yang sudah ada.")
            return
        }

        val mediaButtonReceiverIntent = Intent(Intent.ACTION_MEDIA_BUTTON, null, this, com.motocompanion.receiver.MediaButtonReceiver::class.java)
        val mediaButtonPendingIntent = PendingIntent.getBroadcast(
            this, 0, mediaButtonReceiverIntent, PendingIntent.FLAG_IMMUTABLE
        )

        val sessionActivityIntent = Intent(this, MainActivity::class.java)
        val sessionActivityPendingIntent = PendingIntent.getActivity(
            this, 0, sessionActivityIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        mediaSession = MediaSessionCompat(this, "MotoMediaSession", null, mediaButtonPendingIntent).apply {
            setSessionActivity(sessionActivityPendingIntent)
            setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                        MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
            )

            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPrepare() {
                    Log.i(tag, "onPrepare diterima dari RoadSync / PCX")
                    handlePlay()
                }

                override fun onPrepareFromMediaId(mediaId: String?, extras: Bundle?) {
                    Log.i(tag, "onPrepareFromMediaId: $mediaId")
                    onPlayFromMediaId(mediaId, extras)
                }

                override fun onPrepareFromSearch(query: String?, extras: Bundle?) {
                    Log.i(tag, "onPrepareFromSearch: $query")
                    onPlayFromSearch(query, extras)
                }

                override fun onPlay() {
                    Log.i(tag, "onPlay diterima dari RoadSync / PCX")
                    handlePlay()
                }

                override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
                    Log.i(tag, "onPlayFromMediaId diterima dari RoadSync / PCX: $mediaId")
                    val selectedPkg = MediaControlDispatcher.getSelectedPackage(this@MediaSessionService)
                    if (selectedPkg != MediaControlDispatcher.INTERNAL_PACKAGE_ID) {
                        handlePlay()
                        return
                    }
                    if (mediaId == MEDIA_ITEM_STREAM_ID) {
                        handlePlay()
                    } else {
                        val songId = mediaId?.toLongOrNull()
                        val index = playlist.indexOfFirst { it.id == songId }
                        if (index != -1) {
                            playTrack(index)
                        } else {
                            handlePlay()
                        }
                    }
                }

                override fun onPlayFromSearch(query: String?, extras: Bundle?) {
                    Log.i(tag, "onPlayFromSearch diterima dari RoadSync / PCX: $query")
                    handlePlay()
                }

                override fun onPause() {
                    Log.i(tag, "onPause diterima dari RoadSync / PCX")
                    handlePause()
                }

                override fun onSkipToNext() {
                    Log.i(tag, "onSkipToNext diterima dari RoadSync / PCX")
                    handleNext()
                }

                override fun onSkipToPrevious() {
                    Log.i(tag, "onSkipToPrevious diterima dari RoadSync / PCX")
                    handlePrev()
                }

                override fun onStop() {
                    Log.i(tag, "onStop diterima dari RoadSync / PCX")
                    handleStop()
                }

                override fun onCustomAction(action: String?, extras: Bundle?) {
                    when (action) {
                        ACTION_TOGGLE -> handleToggle()
                    }
                }

                override fun onMediaButtonEvent(mediaButtonEvent: Intent?): Boolean {
                    @Suppress("DEPRECATION")
                    val keyEvent = mediaButtonEvent?.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
                    if (keyEvent != null && keyEvent.action == KeyEvent.ACTION_DOWN) {
                        val selectedPkg = MediaControlDispatcher.getSelectedPackage(this@MediaSessionService)
                        if (selectedPkg != MediaControlDispatcher.INTERNAL_PACKAGE_ID) {
                            when (keyEvent.keyCode) {
                                KeyEvent.KEYCODE_MEDIA_PLAY -> handlePlay()
                                KeyEvent.KEYCODE_MEDIA_PAUSE -> handlePause()
                                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> handleToggle()
                                KeyEvent.KEYCODE_MEDIA_NEXT -> handleNext()
                                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> handlePrev()
                                KeyEvent.KEYCODE_MEDIA_STOP -> handleStop()
                                else -> MediaControlDispatcher.dispatchMediaAction(this@MediaSessionService, keyEvent.keyCode)
                            }
                            return true
                        }
                    }
                    return super.onMediaButtonEvent(mediaButtonEvent)
                }
            })

            isActive = true
        }

        sharedMediaSession = mediaSession
        sessionToken = mediaSession.sessionToken

        // SEGERA INISIALISASI PlaybackState dan Metadata default agar RoadSync & TFT PCX tidak menganggap Spotify mati / belum login
        val selectedName = MediaControlDispatcher.getSelectedName(this)
        val trackInfo = MediaControlDispatcher.getActiveTrackInfo(this)

        val initialTitle = if (trackInfo != null && trackInfo.isPlaying) trackInfo.title else "Putar $selectedName"
        val initialArtist = if (trackInfo != null && trackInfo.isPlaying) trackInfo.artist else "H-Sync Honda"
        val initialState = if (trackInfo != null && trackInfo.isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PLAYING

        val playbackState = PlaybackStateCompat.Builder()
            .setActions(getSupportedActions())
            .setState(initialState, trackInfo?.positionMs ?: 0L, 1.0f, SystemClock.elapsedRealtime())
            .build()
        mediaSession.setPlaybackState(playbackState)

        val metadata = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, MEDIA_ITEM_STREAM_ID)
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, initialTitle)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, initialArtist)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, selectedName)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, initialTitle)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, initialArtist)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, trackInfo?.durationMs ?: 180000L)
            .build()
        mediaSession.setMetadata(metadata)

        lastSyncedTitle = initialTitle
        lastSyncedArtist = initialArtist
        lastSyncedState = initialState

        updateForegroundNotification(initialTitle, initialArtist, true)
        broadcastSpotifyState(initialTitle, initialArtist, selectedName, true, trackInfo?.durationMs ?: 180000L, trackInfo?.positionMs ?: 0L)
    }

    fun loadLocalSongs() {
        playlist.clear()
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DURATION
        )

        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        val sortOrder = "${MediaStore.Audio.Media.TITLE} ASC"

        try {
            contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                null,
                sortOrder
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val durCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val title = cursor.getString(titleCol) ?: "Tidak diketahui"
                    val artist = cursor.getString(artistCol) ?: "Artis Tidak Diketahui"
                    val duration = cursor.getLong(durCol)
                    val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)

                    playlist.add(Song(id, title, artist, duration, uri))
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Gagal memuat lagu dari penyimpanan: ${e.message}")
        }

        sendBroadcast(Intent(BROADCAST_PLAYLIST_UPDATED))
    }

    private fun playTrack(index: Int) {
        if (playlist.isEmpty()) return
        currentIndex = (index + playlist.size) % playlist.size
        val song = playlist[currentIndex]
        currentSong = song

        try {
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                setDataSource(applicationContext, song.contentUri)
                prepare()
                start()
                setOnCompletionListener {
                    nextTrack()
                }
            }

            isPlaying = true
            updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)
            updateMetadata(song)
            updateForegroundNotification(song.title, song.artist, true)
            broadcastSpotifyState(song.title, song.artist, "MotoCompanion Musik", true, song.durationMs, 0L)
            sendBroadcast(Intent(BROADCAST_TRACK_CHANGED))
        } catch (e: Exception) {
            Log.e(tag, "Eror saat memutar lagu: ${e.message}")
        }
    }

    private fun pauseTrack() {
        mediaPlayer?.let {
            if (it.isPlaying) {
                it.pause()
            }
        }
        isPlaying = false
        updatePlaybackState(PlaybackStateCompat.STATE_PAUSED)
        currentSong?.let {
            updateForegroundNotification(it.title, it.artist, false)
            broadcastSpotifyState(it.title, it.artist, "MotoCompanion Musik", false, it.durationMs, mediaPlayer?.currentPosition?.toLong() ?: 0L)
        }
        sendBroadcast(Intent(BROADCAST_TRACK_CHANGED))
    }

    private fun nextTrack() {
        if (playlist.isEmpty()) return
        playTrack(currentIndex + 1)
    }

    private fun prevTrack() {
        if (playlist.isEmpty()) return
        playTrack(currentIndex - 1)
    }

    private fun stopTrack() {
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
        isPlaying = false
        updatePlaybackState(PlaybackStateCompat.STATE_STOPPED)
        currentSong?.let {
            broadcastSpotifyState(it.title, it.artist, "MotoCompanion Musik", false, it.durationMs, 0L)
        }
        sendBroadcast(Intent(BROADCAST_TRACK_CHANGED))
    }

    private fun updatePlaybackState(state: Int) {
        val playbackState = PlaybackStateCompat.Builder()
            .setActions(getSupportedActions())
            .setState(state, mediaPlayer?.currentPosition?.toLong() ?: 0L, if (state == PlaybackStateCompat.STATE_PLAYING) 1.0f else 0.0f, SystemClock.elapsedRealtime())
            .build()

        mediaSession.setPlaybackState(playbackState)
    }

    private fun updateMetadata(song: Song) {
        val metadata = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, song.id.toString())
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, song.title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, song.artist)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, "MotoCompanion Musik")
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, song.title)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, song.artist)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, song.durationMs)
            .build()

        mediaSession.setMetadata(metadata)
    }

    /**
     * Memperbarui notifikasi foreground resmi bergaya MediaStyle agar Android & Honda RoadSync
     * mendeteksi bahwa pemutar musik sedang aktif dan tidak menghentikannya saat berkendara.
     */
    fun updateForegroundNotification(title: String, artist: String, isPlaying: Boolean) {
        try {
            val openIntent = Intent(this, MainActivity::class.java)
            val pendingOpen = PendingIntent.getActivity(this, 0, openIntent, PendingIntent.FLAG_IMMUTABLE)

            val playPauseAction = if (isPlaying) {
                NotificationCompat.Action(
                    android.R.drawable.ic_media_pause,
                    "Pause",
                    MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_PAUSE)
                )
            } else {
                NotificationCompat.Action(
                    android.R.drawable.ic_media_play,
                    "Play",
                    MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_PLAY)
                )
            }

            val nextAction = NotificationCompat.Action(
                android.R.drawable.ic_media_next,
                "Next",
                MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_SKIP_TO_NEXT)
            )

            val prevAction = NotificationCompat.Action(
                android.R.drawable.ic_media_previous,
                "Previous",
                MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS)
            )

            val notification = NotificationCompat.Builder(this, App.CHANNEL_MEDIA_SERVICE)
                .setContentTitle(title)
                .setContentText(artist)
                .setSmallIcon(R.drawable.ic_bike)
                .setContentIntent(pendingOpen)
                .addAction(prevAction)
                .addAction(playPauseAction)
                .addAction(nextAction)
                .setStyle(
                    androidx.media.app.NotificationCompat.MediaStyle()
                        .setMediaSession(mediaSession.sessionToken)
                        .setShowActionsInCompactView(0, 1, 2)
                )
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOngoing(isPlaying)
                .build()

            startForeground(1001, notification)
        } catch (e: Exception) {
            Log.e(tag, "Gagal update foreground notification: ${e.message}")
        }
    }

    /**
     * Mengirimkan Broadcast Resmi Spotify (com.spotify.music.playbackstatechanged & metadatachanged)
     * yang dimonitor oleh aplikasi dashboard seperti Honda RoadSync (Drivemode).
     */
    fun broadcastSpotifyState(title: String, artist: String, album: String, isPlaying: Boolean, durationMs: Long, positionMs: Long) {
        try {
            val stateIntent = Intent("com.spotify.music.playbackstatechanged").apply {
                putExtra("playing", isPlaying)
                putExtra("playbackPosition", positionMs.toInt())
                putExtra("timeSent", System.currentTimeMillis())
            }
            sendBroadcast(stateIntent)

            val metaIntent = Intent("com.spotify.music.metadatachanged").apply {
                putExtra("id", "spotify:track:motocompanion")
                putExtra("artist", artist)
                putExtra("album", album)
                putExtra("track", title)
                putExtra("length", (durationMs / 1000).toInt())
                putExtra("timeSent", System.currentTimeMillis())
            }
            sendBroadcast(metaIntent)

            val queueIntent = Intent("com.spotify.music.queuechanged").apply {
                putExtra("timeSent", System.currentTimeMillis())
            }
            sendBroadcast(queueIntent)
        } catch (e: Exception) {
            Log.e(tag, "Gagal broadcast Spotify intents: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        MediaButtonReceiver.handleIntent(mediaSession, intent)
        when (intent?.action) {
            ACTION_STOP_SERVICE -> {
                handleStopService()
                return START_NOT_STICKY
            }
            ACTION_START_FOREGROUND -> {
                val selectedName = MediaControlDispatcher.getSelectedName(this)
                val trackInfo = MediaControlDispatcher.getActiveTrackInfo(this)
                val title = if (trackInfo != null && trackInfo.isPlaying) trackInfo.title else "Siap Terhubung"
                val artist = if (trackInfo != null && trackInfo.isPlaying) trackInfo.artist else "H-Sync Honda RoadSync"
                updateForegroundNotification(title, artist, trackInfo?.isPlaying ?: false)
            }
            ACTION_PLAY -> handlePlay()
            ACTION_PAUSE -> handlePause()
            ACTION_NEXT -> handleNext()
            ACTION_PREV -> handlePrev()
            ACTION_TOGGLE -> handleToggle()
        }
        return START_NOT_STICKY
    }

    private fun handleStopService() {
        Log.i(tag, "handleStopService: Menghentikan service dan membersihkan notifikasi latar belakang")
        syncHandler.removeCallbacksAndMessages(null)
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
        isPlaying = false
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {}
        broadcastSpotifyState("H-Sync Standby", "Honda RoadSync", "H-Sync", false, 0L, 0L)
        stopSelf()
    }

    private fun handlePlay() {
        val selectedPkg = MediaControlDispatcher.getSelectedPackage(this)
        if (selectedPkg == MediaControlDispatcher.INTERNAL_PACKAGE_ID) {
            if (!isPlaying) playTrack(currentIndex)
        } else {
            // 1. Seketika ubah state MediaSession ke STATE_PLAYING agar RoadSync & TFT PCX tidak menganggap timeout / eror
            forcePlayingState()
            // 2. Meneruskan perintah Play ke pemutar yang dipilih (misal BitChord)
            MediaControlDispatcher.dispatchMediaAction(this, KeyEvent.KEYCODE_MEDIA_PLAY)
            // 3. Sinkronisasi metadata lagu dari pemutar tersebut
            syncExternalPlayer()
            syncHandler.postDelayed({ syncExternalPlayer() }, 300L)
            syncHandler.postDelayed({ syncExternalPlayer() }, 800L)
            syncHandler.postDelayed({ syncExternalPlayer() }, 1500L)
        }
    }

    private fun handlePause() {
        val selectedPkg = MediaControlDispatcher.getSelectedPackage(this)
        if (selectedPkg == MediaControlDispatcher.INTERNAL_PACKAGE_ID) {
            if (isPlaying) pauseTrack()
        } else {
            forcePausedState()
            MediaControlDispatcher.dispatchMediaAction(this, KeyEvent.KEYCODE_MEDIA_PAUSE)
            syncExternalPlayer()
            syncHandler.postDelayed({ syncExternalPlayer() }, 300L)
        }
    }

    private fun handleNext() {
        val selectedPkg = MediaControlDispatcher.getSelectedPackage(this)
        if (selectedPkg == MediaControlDispatcher.INTERNAL_PACKAGE_ID) {
            nextTrack()
        } else {
            MediaControlDispatcher.dispatchMediaAction(this, KeyEvent.KEYCODE_MEDIA_NEXT)
            forcePlayingState()
            syncHandler.postDelayed({ syncExternalPlayer() }, 400L)
            syncHandler.postDelayed({ syncExternalPlayer() }, 1200L)
        }
    }

    private fun handlePrev() {
        val selectedPkg = MediaControlDispatcher.getSelectedPackage(this)
        if (selectedPkg == MediaControlDispatcher.INTERNAL_PACKAGE_ID) {
            prevTrack()
        } else {
            MediaControlDispatcher.dispatchMediaAction(this, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            forcePlayingState()
            syncHandler.postDelayed({ syncExternalPlayer() }, 400L)
            syncHandler.postDelayed({ syncExternalPlayer() }, 1200L)
        }
    }

    private fun handleToggle() {
        val selectedPkg = MediaControlDispatcher.getSelectedPackage(this)
        if (selectedPkg == MediaControlDispatcher.INTERNAL_PACKAGE_ID) {
            if (isPlaying) pauseTrack() else playTrack(currentIndex)
        } else {
            val trackInfo = MediaControlDispatcher.getActiveTrackInfo(this)
            if (trackInfo?.isPlaying == true) {
                handlePause()
            } else {
                handlePlay()
            }
        }
    }

    private fun handleStop() {
        val selectedPkg = MediaControlDispatcher.getSelectedPackage(this)
        if (selectedPkg == MediaControlDispatcher.INTERNAL_PACKAGE_ID) {
            stopTrack()
        } else {
            forcePausedState()
            MediaControlDispatcher.dispatchMediaAction(this, KeyEvent.KEYCODE_MEDIA_STOP)
            syncExternalPlayer()
        }
    }

    fun forcePlayingState() {
        val selectedName = MediaControlDispatcher.getSelectedName(this)
        val trackInfo = MediaControlDispatcher.getActiveTrackInfo(this)

        val title = if (trackInfo != null && trackInfo.title.isNotBlank() && trackInfo.title != "Putar $selectedName") {
            trackInfo.title
        } else {
            "Memutar $selectedName"
        }

        val artist = if (trackInfo != null && trackInfo.artist.isNotBlank() && trackInfo.artist != "H-Sync Honda") {
            trackInfo.artist
        } else {
            "H-Sync Honda"
        }

        val duration = if (trackInfo != null && trackInfo.durationMs > 0) trackInfo.durationMs else 180000L
        val position = trackInfo?.positionMs ?: 0L

        val metadata = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, MEDIA_ITEM_STREAM_ID)
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, trackInfo?.appName ?: selectedName)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, title)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, artist)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration)
            .build()
        mediaSession.setMetadata(metadata)

        val playbackState = PlaybackStateCompat.Builder()
            .setActions(getSupportedActions())
            .setState(PlaybackStateCompat.STATE_PLAYING, position, 1.0f, SystemClock.elapsedRealtime())
            .build()
        mediaSession.setPlaybackState(playbackState)

        lastSyncedTitle = title
        lastSyncedArtist = artist
        lastSyncedState = PlaybackStateCompat.STATE_PLAYING

        updateForegroundNotification(title, artist, true)
        broadcastSpotifyState(title, artist, selectedName, true, duration, position)
    }

    fun forcePausedState() {
        val selectedName = MediaControlDispatcher.getSelectedName(this)
        val trackInfo = MediaControlDispatcher.getActiveTrackInfo(this)

        val title = if (trackInfo != null && trackInfo.title.isNotBlank()) trackInfo.title else "Jeda $selectedName"
        val artist = if (trackInfo != null && trackInfo.artist.isNotBlank()) trackInfo.artist else "H-Sync Honda"
        val duration = if (trackInfo != null && trackInfo.durationMs > 0) trackInfo.durationMs else 180000L
        val position = trackInfo?.positionMs ?: 0L

        val metadata = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, MEDIA_ITEM_STREAM_ID)
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, trackInfo?.appName ?: selectedName)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, title)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, artist)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration)
            .build()
        mediaSession.setMetadata(metadata)

        val playbackState = PlaybackStateCompat.Builder()
            .setActions(getSupportedActions())
            .setState(PlaybackStateCompat.STATE_PAUSED, position, 0.0f, SystemClock.elapsedRealtime())
            .build()
        mediaSession.setPlaybackState(playbackState)

        lastSyncedTitle = title
        lastSyncedArtist = artist
        lastSyncedState = PlaybackStateCompat.STATE_PAUSED

        updateForegroundNotification(title, artist, false)
        broadcastSpotifyState(title, artist, selectedName, false, duration, position)
    }

    fun syncExternalPlayer() {
        val trackInfo = MediaControlDispatcher.getActiveTrackInfo(this) ?: return
        val selectedName = MediaControlDispatcher.getSelectedName(this)

        val title = if (trackInfo.title.isNotBlank()) trackInfo.title else "Putar $selectedName"
        val artist = if (trackInfo.artist.isNotBlank()) trackInfo.artist else "H-Sync Honda"
        val targetState = if (trackInfo.isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
        val duration = if (trackInfo.durationMs > 0) trackInfo.durationMs else 180000L

        var changed = false

        if (title != lastSyncedTitle || artist != lastSyncedArtist) {
            lastSyncedTitle = title
            lastSyncedArtist = artist
            val metadata = MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, MEDIA_ITEM_STREAM_ID)
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, trackInfo.appName.ifBlank { selectedName })
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, title)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, artist)
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration)
                .build()
            mediaSession.setMetadata(metadata)
            changed = true
            Log.d(tag, "Synced track info ke RoadSync & TFT PCX: $title - $artist")
        }

        if (targetState != lastSyncedState || changed) {
            lastSyncedState = targetState
            val playbackState = PlaybackStateCompat.Builder()
                .setActions(getSupportedActions())
                .setState(targetState, trackInfo.positionMs, if (targetState == PlaybackStateCompat.STATE_PLAYING) 1.0f else 0.0f, SystemClock.elapsedRealtime())
                .build()
            mediaSession.setPlaybackState(playbackState)
            Log.d(tag, "Synced state ke RoadSync & TFT PCX: $targetState")
        }

        val isNowPlaying = (targetState == PlaybackStateCompat.STATE_PLAYING)
        updateForegroundNotification(title, artist, isNowPlaying)
        broadcastSpotifyState(title, artist, trackInfo.appName.ifBlank { selectedName }, isNowPlaying, duration, trackInfo.positionMs)
    }

    fun updateExternalPlayerMetadata() {
        syncExternalPlayer()
    }

    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): BrowserRoot {
        Log.i(tag, "MediaBrowser client connected: $clientPackageName")
        val selectedPkg = MediaControlDispatcher.getSelectedPackage(this)
        if (selectedPkg != MediaControlDispatcher.INTERNAL_PACKAGE_ID) {
            syncExternalPlayer()
        }

        val rootExtras = Bundle().apply {
            putBoolean("android.media.browse.SEARCH_SUPPORTED", true)
            putBoolean("androidx.media.MediaBrowserCompat.Extras.KEY_SEARCH_SUPPORTED", true)
            putBoolean("android.service.media.extra.OFFLINE", false)
        }
        return BrowserRoot(MEDIA_ROOT_ID, rootExtras)
    }

    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaBrowserCompat.MediaItem>>) {
        val mediaItems = mutableListOf<MediaBrowserCompat.MediaItem>()
        val selectedName = MediaControlDispatcher.getSelectedName(this)
        val trackInfo = MediaControlDispatcher.getActiveTrackInfo(this)

        val currentTitle = if (trackInfo != null && trackInfo.isPlaying) trackInfo.title else "▶ Putar $selectedName"
        val currentArtist = if (trackInfo != null && trackInfo.isPlaying) trackInfo.artist else "Kontrol Honda PCX via MotoCompanion"

        val streamDesc = MediaDescriptionCompat.Builder()
            .setMediaId(MEDIA_ITEM_STREAM_ID)
            .setTitle(currentTitle)
            .setSubtitle(currentArtist)
            .setDescription("Meneruskan tombol stang ke pemutar musik terpilih")
            .build()
        mediaItems.add(MediaBrowserCompat.MediaItem(streamDesc, MediaBrowserCompat.MediaItem.FLAG_PLAYABLE))

        for (song in playlist) {
            val desc = MediaDescriptionCompat.Builder()
                .setMediaId(song.id.toString())
                .setTitle(song.title)
                .setSubtitle(song.artist)
                .build()
            mediaItems.add(MediaBrowserCompat.MediaItem(desc, MediaBrowserCompat.MediaItem.FLAG_PLAYABLE))
        }

        result.sendResult(mediaItems)
    }

    override fun onDestroy() {
        super.onDestroy()
        syncHandler.removeCallbacksAndMessages(null)
        mediaPlayer?.release()
    }
}

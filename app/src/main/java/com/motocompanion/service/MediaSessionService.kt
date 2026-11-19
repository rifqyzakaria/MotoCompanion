package com.motocompanion.service

import android.app.Notification
import android.app.PendingIntent
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Bundle
import android.provider.MediaStore
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.media.MediaBrowserServiceCompat
import androidx.media.session.MediaButtonReceiver
import com.motocompanion.App
import com.motocompanion.R
import com.motocompanion.model.Song
import com.motocompanion.ui.MusicActivity

class MediaSessionService : MediaBrowserServiceCompat() {

    private val tag = "MotoMediaSession"

    companion object {
        const val ACTION_PLAY = "com.motocompanion.ACTION_PLAY"
        const val ACTION_PAUSE = "com.motocompanion.ACTION_PAUSE"
        const val ACTION_NEXT = "com.motocompanion.ACTION_NEXT"
        const val ACTION_PREV = "com.motocompanion.ACTION_PREV"
        const val ACTION_TOGGLE = "com.motocompanion.ACTION_TOGGLE"

        const val BROADCAST_PLAYLIST_UPDATED = "com.motocompanion.PLAYLIST_UPDATED"
        const val BROADCAST_TRACK_CHANGED = "com.motocompanion.TRACK_CHANGED"

        var currentSong: Song? = null
            private set
        var isPlaying: Boolean = false
            private set

        val playlist = mutableListOf<Song>()
        var currentIndex = 0
    }

    private lateinit var mediaSession: MediaSessionCompat
    private var mediaPlayer: MediaPlayer? = null

    override fun onCreate() {
        super.onCreate()

        initMediaSession()
        loadLocalSongs()
    }

    private fun initMediaSession() {
        val mediaButtonReceiverIntent = Intent(Intent.ACTION_MEDIA_BUTTON, null, this, com.motocompanion.receiver.MediaButtonReceiver::class.java)
        val mediaButtonPendingIntent = PendingIntent.getBroadcast(
            this, 0, mediaButtonReceiverIntent, PendingIntent.FLAG_IMMUTABLE
        )

        mediaSession = MediaSessionCompat(this, "MotoMediaSession", null, mediaButtonPendingIntent).apply {
            setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                        MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
            )

            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    playTrack(currentIndex)
                }

                override fun onPause() {
                    pauseTrack()
                }

                override fun onSkipToNext() {
                    nextTrack()
                }

                override fun onSkipToPrevious() {
                    prevTrack()
                }

                override fun onStop() {
                    stopTrack()
                }

                override fun onCustomAction(action: String?, extras: Bundle?) {
                    when (action) {
                        ACTION_TOGGLE -> {
                            if (isPlaying) pauseTrack() else playTrack(currentIndex)
                        }
                    }
                }
            })

            isActive = true
        }

        sessionToken = mediaSession.sessionToken
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
            startForeground(1001, createNotification(song, true))
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
            startForeground(1001, createNotification(it, false))
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
        stopForeground(STOP_FOREGROUND_REMOVE)
        sendBroadcast(Intent(BROADCAST_TRACK_CHANGED))
    }

    private fun updatePlaybackState(state: Int) {
        val playbackState = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackStateCompat.ACTION_STOP
            )
            .setState(state, mediaPlayer?.currentPosition?.toLong() ?: 0L, 1.0f)
            .build()

        mediaSession.setPlaybackState(playbackState)
    }

    private fun updateMetadata(song: Song) {
        val metadata = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, song.title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, song.artist)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, song.durationMs)
            .build()

        mediaSession.setMetadata(metadata)
    }

    private fun createNotification(song: Song, isPlaying: Boolean): Notification {
        val openIntent = Intent(this, MusicActivity::class.java)
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

        return NotificationCompat.Builder(this, App.CHANNEL_MEDIA_SERVICE)
            .setContentTitle(song.title)
            .setContentText(song.artist)
            .setSmallIcon(android.R.drawable.ic_media_play)
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
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        MediaButtonReceiver.handleIntent(mediaSession, intent)
        when (intent?.action) {
            ACTION_PLAY -> if (!isPlaying) playTrack(currentIndex)
            ACTION_PAUSE -> if (isPlaying) pauseTrack()
            ACTION_NEXT -> nextTrack()
            ACTION_PREV -> prevTrack()
            ACTION_TOGGLE -> if (isPlaying) pauseTrack() else playTrack(currentIndex)
        }
        return START_STICKY
    }

    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): BrowserRoot {
        return BrowserRoot("root_id", null)
    }

    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaBrowserCompat.MediaItem>>) {
        result.sendResult(mutableListOf())
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaPlayer?.release()
        mediaSession.release()
    }
}

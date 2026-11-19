package com.motocompanion.ui

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.motocompanion.databinding.ActivityMusicBinding
import com.motocompanion.databinding.ItemSongBinding
import com.motocompanion.model.Song
import com.motocompanion.service.MediaSessionService

class MusicActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMusicBinding
    private val songs = mutableListOf<Song>()
    private lateinit var adapter: SongAdapter
    private val audioPermissionReqCode = 102

    private val mediaReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                MediaSessionService.BROADCAST_PLAYLIST_UPDATED -> {
                    updatePlaylist()
                }
                MediaSessionService.BROADCAST_TRACK_CHANGED -> {
                    updatePlayerUi()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMusicBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerView()
        setupListeners()
        checkAudioPermissionAndLoad()
    }

    private fun setupRecyclerView() {
        adapter = SongAdapter(songs) { song, index ->
            MediaSessionService.currentIndex = index
            sendMediaAction(MediaSessionService.ACTION_PLAY)
        }
        binding.rvSongs.layoutManager = LinearLayoutManager(this)
        binding.rvSongs.adapter = adapter
    }

    private fun setupListeners() {
        binding.btnBackMusic.setOnClickListener { finish() }

        binding.btnRefreshSongs.setOnClickListener {
            val serviceIntent = Intent(this, MediaSessionService::class.java)
            startService(serviceIntent)
            checkAudioPermissionAndLoad()
        }

        binding.btnPlayPauseSong.setOnClickListener {
            sendMediaAction(MediaSessionService.ACTION_TOGGLE)
        }

        binding.btnNextSong.setOnClickListener {
            sendMediaAction(MediaSessionService.ACTION_NEXT)
        }

        binding.btnPrevSong.setOnClickListener {
            sendMediaAction(MediaSessionService.ACTION_PREV)
        }
    }

    private fun sendMediaAction(action: String) {
        val intent = Intent(this, MediaSessionService::class.java).apply {
            this.action = action
        }
        startService(intent)
    }

    private fun checkAudioPermissionAndLoad() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            val serviceIntent = Intent(this, MediaSessionService::class.java)
            startService(serviceIntent)
            updatePlaylist()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(permission), audioPermissionReqCode)
        }
    }

    private fun updatePlaylist() {
        songs.clear()
        songs.addAll(MediaSessionService.playlist)
        adapter.notifyDataSetChanged()
        updatePlayerUi()
    }

    private fun updatePlayerUi() {
        val current = MediaSessionService.currentSong
        if (current != null) {
            binding.tvCurrentPlayingTitle.text = current.title
            binding.tvCurrentPlayingArtist.text = current.artist
        }

        binding.btnPlayPauseSong.text = if (MediaSessionService.isPlaying) "PAUSE" else "PLAY"
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter().apply {
            addAction(MediaSessionService.BROADCAST_PLAYLIST_UPDATED)
            addAction(MediaSessionService.BROADCAST_TRACK_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(mediaReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(mediaReceiver, filter)
        }
        updatePlayerUi()
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(mediaReceiver)
        } catch (_: Exception) {}
    }

    private class SongAdapter(
        private val list: List<Song>,
        private val onItemClick: (Song, Int) -> Unit
    ) : RecyclerView.Adapter<SongAdapter.ViewHolder>() {

        class ViewHolder(val itemBinding: ItemSongBinding) : RecyclerView.ViewHolder(itemBinding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val itemBinding = ItemSongBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return ViewHolder(itemBinding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val song = list[position]
            holder.itemBinding.tvSongTitle.text = song.title
            holder.itemBinding.tvSongArtist.text = song.artist
            holder.itemView.setOnClickListener {
                onItemClick(song, position)
            }
        }

        override fun getItemCount(): Int = list.size
    }
}

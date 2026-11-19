package com.motocompanion.ui

import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.motocompanion.R
import com.motocompanion.databinding.ActivityPlayerSelectorBinding
import com.motocompanion.databinding.ItemPlayerAppBinding
import com.motocompanion.model.InstalledPlayer
import com.motocompanion.util.MediaControlDispatcher

class PlayerSelectorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlayerSelectorBinding
    private val playerList = mutableListOf<InstalledPlayer>()
    private lateinit var adapter: PlayerAdapter
    private var selectedPackage: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerSelectorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        selectedPackage = MediaControlDispatcher.getSelectedPackage(this)
        setupRecyclerView()
        setupListeners()
        loadInstalledPlayers(false)
        updateSelectedUi()
    }

    private fun setupRecyclerView() {
        adapter = PlayerAdapter(playerList, selectedPackage) { player ->
            selectedPackage = player.packageName
            MediaControlDispatcher.setSelectedPlayer(this, player.packageName, player.appName)
            adapter.setSelectedPackage(selectedPackage)
            updateSelectedUi()
            Toast.makeText(this, "Pemutar aktif: ${player.appName}", Toast.LENGTH_SHORT).show()
        }
        binding.rvPlayers.layoutManager = LinearLayoutManager(this)
        binding.rvPlayers.adapter = adapter
    }

    private fun setupListeners() {
        binding.btnBack.setOnClickListener { finish() }

        binding.switchShowAllApps.setOnCheckedChangeListener { _, isChecked ->
            loadInstalledPlayers(isChecked)
        }

        binding.btnLaunchPlayer.setOnClickListener {
            MediaControlDispatcher.launchSelectedPlayer(this)
        }

        binding.btnTestPlayPause.setOnClickListener {
            MediaControlDispatcher.dispatchMediaAction(this, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            Toast.makeText(this, "Perintah Play/Pause dikirim ke $selectedPackage", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadInstalledPlayers(showAll: Boolean) {
        playerList.clear()
        playerList.addAll(MediaControlDispatcher.getInstalledMediaPlayers(this, showAll))
        adapter.notifyDataSetChanged()
    }

    private fun updateSelectedUi() {
        val currentName = MediaControlDispatcher.getSelectedName(this)
        binding.tvCurrentSelected.text = "Terpilih: $currentName"
    }

    private class PlayerAdapter(
        private val list: List<InstalledPlayer>,
        private var currentSelectedPkg: String,
        private val onItemClick: (InstalledPlayer) -> Unit
    ) : RecyclerView.Adapter<PlayerAdapter.ViewHolder>() {

        fun setSelectedPackage(pkg: String) {
            currentSelectedPkg = pkg
            notifyDataSetChanged()
        }

        class ViewHolder(val binding: ItemPlayerAppBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemPlayerAppBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val player = list[position]
            holder.binding.tvAppName.text = player.appName
            holder.binding.tvPackageName.text = player.packageName

            if (player.icon != null) {
                holder.binding.ivAppIcon.setImageDrawable(player.icon)
            } else {
                holder.binding.ivAppIcon.setImageResource(R.drawable.ic_bike)
            }

            // Label jenis
            if (player.isInternal) {
                holder.binding.tvAppType.text = "Bawaan"
            } else if (player.isVideoPlayer) {
                holder.binding.tvAppType.text = "Video / Musik"
            } else {
                holder.binding.tvAppType.text = "Musik"
            }

            holder.binding.rbSelected.isChecked = (player.packageName == currentSelectedPkg)

            holder.itemView.setOnClickListener {
                onItemClick(player)
            }
        }

        override fun getItemCount(): Int = list.size
    }
}

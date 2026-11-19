package com.motocompanion.model

import android.graphics.drawable.Drawable

data class InstalledPlayer(
    val appName: String,
    val packageName: String,
    val icon: Drawable?,
    val isInternal: Boolean = false,
    val isVideoPlayer: Boolean = false
)

package com.motocompanion.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.view.KeyEvent
import com.motocompanion.util.MediaControlDispatcher

class MediaButtonReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return

        if (Intent.ACTION_MEDIA_BUTTON == intent.action) {
            @Suppress("DEPRECATION")
            val keyEvent = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
            if (keyEvent != null && keyEvent.action == KeyEvent.ACTION_DOWN) {
                MediaControlDispatcher.dispatchMediaAction(context, keyEvent.keyCode)
            }
        }
    }
}

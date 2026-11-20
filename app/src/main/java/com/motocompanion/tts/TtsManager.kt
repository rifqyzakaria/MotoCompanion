package com.motocompanion.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.motocompanion.util.LocaleThemeManager
import java.util.Locale
import java.util.UUID

class TtsManager(private val context: Context) : TextToSpeech.OnInitListener {

    private val tag = "TtsManager"
    private var tts: TextToSpeech? = null
    private var isInitialized = false

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null

    var speechRate: Float = 1.0f
        set(value) {
            field = value
            tts?.setSpeechRate(value)
        }

    init {
        tts = TextToSpeech(context.applicationContext, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val savedLang = LocaleThemeManager.getLanguage(context)
            val targetLocale = when (savedLang) {
                LocaleThemeManager.LANG_EN -> Locale.ENGLISH
                LocaleThemeManager.LANG_IN -> Locale("id", "ID")
                else -> {
                    val def = Locale.getDefault()
                    if (def.language.equals("en", ignoreCase = true)) Locale.ENGLISH else Locale("id", "ID")
                }
            }
            setLanguage(targetLocale)

            tts?.setSpeechRate(speechRate)
            tts?.setPitch(1.0f)
            setupProgressListener()
            isInitialized = true
        } else {
            Log.e(tag, "Gagal menginisialisasi TextToSpeech, status: $status")
        }
    }

    fun setLanguage(locale: Locale) {
        val result = tts?.setLanguage(locale)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            Log.w(tag, "Bahasa $locale tidak tersedia langsung, mencoba fallback ke default sistem")
            tts?.setLanguage(Locale.getDefault())
        } else {
            Log.i(tag, "TTS bahasa berhasil disetel ke: $locale")
        }
    }

    private fun setupProgressListener() {
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                Log.d(tag, "TTS Mulai berbicara: $utteranceId")
            }

            override fun onDone(utteranceId: String?) {
                Log.d(tag, "TTS Selesai berbicara: $utteranceId")
                abandonAudioFocus()
            }

            override fun onError(utteranceId: String?) {
                Log.e(tag, "TTS Eror saat berbicara: $utteranceId")
                abandonAudioFocus()
            }
        })
    }

    /**
     * Membacakan teks ke Intercom / Headset dengan Audio Ducking
     */
    fun speak(text: String, isPriority: Boolean = false) {
        if (!isInitialized) {
            Log.w(tag, "TTS belum siap")
            return
        }

        // Minta Audio Focus sementara (agar musik otomatis mengecil saat suara TTS keluar)
        requestAudioFocus()

        val utteranceId = UUID.randomUUID().toString()
        val queueMode = if (isPriority) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD

        val params = Bundle().apply {
            putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
        }

        tts?.speak(text, queueMode, params, utteranceId)
    }

    fun stop() {
        tts?.stop()
        abandonAudioFocus()
    }

    private fun requestAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val playbackAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()

            val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(playbackAttributes)
                .setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener { focusChange ->
                    Log.d(tag, "AudioFocus change: $focusChange")
                }
                .build()

            audioFocusRequest = focusRequest
            audioManager.requestAudioFocus(focusRequest)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                null,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            )
        }
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
    }

    fun shutdown() {
        stop()
        tts?.shutdown()
        tts = null
        isInitialized = false
    }
}

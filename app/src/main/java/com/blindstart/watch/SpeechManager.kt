package com.blindstart.watch

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.speech.tts.TextToSpeech
import android.util.Log
import android.widget.Toast
import java.util.Locale

/**
 * Thin wrapper around Android's TextToSpeech engine.
 *
 * - [announce] is used for immediate, important feedback (button presses, mode/state
 *   changes). It FLUSHES the speech queue so it's never stuck behind a stale countdown tick.
 * - [say] is used for the periodic countdown ticks. It ADDS to the queue so the numbers
 *   are spoken in order without cutting each other off.
 *
 * A very common reason a TTS app goes completely silent is that the device's default
 * locale has no voice data installed for the selected engine (speak() then just fails
 * quietly). We detect that and fall back to English, which is present on virtually every
 * Android TTS install, and surface a Toast if TTS truly isn't usable so it's obvious what's
 * wrong instead of the app looking "broken".
 */
class SpeechManager(private val context: Context) : TextToSpeech.OnInitListener {

    companion object {
        private const val TAG = "SpeechManager"
    }

    private var tts: TextToSpeech? = null
    private var ready = false
    private var explicitEngineRetryDone = false
    private val pendingBeforeReady = mutableListOf<Pair<String, Boolean>>()

    init {
        tts = TextToSpeech(context.applicationContext, this)
    }

    override fun onInit(status: Int) {
        Log.d(TAG, "onInit status=$status, engine=${tts?.defaultVoice?.name}")

        if (status != TextToSpeech.SUCCESS) {
            val engines = availableEnginePackages()

            Log.e(
                TAG,
                "TTS initialization failed: status=$status, availableEngines=$engines"
            )

            ready = false
            pendingBeforeReady.clear()

            Toast.makeText(
                context,
                "Text-to-speech could not be started. Check Text-to-speech settings.",
                Toast.LENGTH_LONG
            ).show()

            promptInstallTtsData()
            return
        }


        val localeUsable = applyUsableLocale()

        Log.d(
            TAG,
            "locale=${
                tts?.language
            }, voice=${tts?.voice?.name}, voices=${tts?.voices?.size}, usable=$localeUsable"
        )

        if (!localeUsable) {
            ready = false
            return
        }

        tts?.setSpeechRate(1.0f)
        ready = true

        val pending = pendingBeforeReady.toList()
        pendingBeforeReady.clear()

        pending.forEach { (text, flush) ->
            speak(text, flush)
        }

        // Test speech immediately after initialization
        speak("Text to speech is ready", flush = true)
    }



    /** Packages on the device that declare a TTS engine service, visible via the manifest <queries> entry. */
    private fun availableEnginePackages(): List<String> {
        return try {
            val intent = Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE)

            context.packageManager
                .queryIntentServices(intent, 0)
                .mapNotNull { resolveInfo ->
                    resolveInfo.serviceInfo?.packageName
                }
                .distinct()
                .also {
                    Log.d(TAG, "Discovered TTS services: $it")
                }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to query installed TTS engines", e)
            emptyList()
        }
    }


    /**
     * "TTS engine could not start" almost always means the device has no TTS engine
     * installed/selected, or the selected engine has no voice data downloaded. Rather than
     * just telling the person that, launch the system flow that lets them install one -
     * this is the same screen Android's own Settings > Text-to-speech uses.
     */
    private fun promptInstallTtsData() {
        try {
            val installIntent = Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)
            installIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            context.startActivity(installIntent)
        } catch (e: ActivityNotFoundException) {
            Log.e(TAG, "No activity available to install TTS data", e)
        }
    }

    /** Tries the device's default locale first, then falls back to English/US. */
    private fun applyUsableLocale(): Boolean {
        val default = Locale.getDefault()
        val defaultResult = tts?.setLanguage(default) ?: TextToSpeech.LANG_NOT_SUPPORTED
        if (defaultResult != TextToSpeech.LANG_MISSING_DATA && defaultResult != TextToSpeech.LANG_NOT_SUPPORTED) {
            return true
        }

        Log.w(TAG, "TTS voice data missing for $default, falling back to English")
        val fallbackResult = tts?.setLanguage(Locale.US) ?: TextToSpeech.LANG_NOT_SUPPORTED
        return fallbackResult != TextToSpeech.LANG_MISSING_DATA && fallbackResult != TextToSpeech.LANG_NOT_SUPPORTED
    }

    /** Immediate, interrupting announcement (button names, state changes). */
    fun announce(text: String) = speak(text, flush = true)

    /** Queued announcement (countdown ticks) - keeps ordering intact. */
    fun say(text: String) = speak(text, flush = false)

    private fun speak(text: String, flush: Boolean) {
        if (!ready) {
            Log.d(TAG, "Queued before TTS ready: $text")
            pendingBeforeReady.add(text to flush)
            return
        }

        val engine = tts
        if (engine == null) {
            Log.e(TAG, "Cannot speak: TTS instance is null")
            return
        }

        val queueMode = if (flush) {
            TextToSpeech.QUEUE_FLUSH
        } else {
            TextToSpeech.QUEUE_ADD
        }

        val utteranceId = "utterance_${System.nanoTime()}"

        val result = engine.speak(text, queueMode, null, utteranceId)

        Log.d(
            TAG,
            "speak(text=$text, result=$result, ready=$ready, language=${engine.language}, voice=${engine.voice?.name})"
        )

        if (result == TextToSpeech.ERROR) {
            Log.e(TAG, "TTS speak() returned ERROR")
        }
    }


    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
    }
}

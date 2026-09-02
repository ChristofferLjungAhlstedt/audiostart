package com.audiostart.watch

import android.media.AudioManager
import android.media.ToneGenerator

/**
 * Plays a loud attention-getting beep for the start signal instead of speaking
 * "0" / "start".
 *
 * Uses STREAM_MUSIC (the same stream as the spoken announcements and the volume
 * rocker most people actually use) rather than STREAM_ALARM. STREAM_ALARM is
 * controlled by a separate, easy-to-forget alarm-volume slider - if that happens
 * to be muted, ToneGenerator produces no sound at all even though everything else
 * in the app is working, which is a confusing silent failure. If you want the beep
 * on the (potentially louder, but separately-controlled) alarm stream instead, swap
 * AudioManager.STREAM_MUSIC below for AudioManager.STREAM_ALARM.
 */
class BeepPlayer {

    private var toneGenerator: ToneGenerator? = try {
        ToneGenerator(AudioManager.STREAM_MUSIC, ToneGenerator.MAX_VOLUME)
    } catch (e: RuntimeException) {
        null
    }

    fun playStartBeep() {
        toneGenerator?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 1500)
    }

    /** Short, quieter tick used for internal testing / optional future use. */
    fun playTick() {
        toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
    }

    fun release() {
        toneGenerator?.release()
        toneGenerator = null
    }
}

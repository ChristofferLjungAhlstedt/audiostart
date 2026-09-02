package com.blindstart.watch

import android.os.Handler
import android.os.Looper

/** STANDBY = idle/configuring. COUNTDOWN = racing down to the start. COUNTUP = elapsed time since start. */
enum class Phase { STANDBY, COUNTDOWN, COUNTUP }

enum class SequenceMode(val seconds: Int, val label: String) {
    FIVE_MIN(5 * 60, "5 minutes"),
    THREE_MIN(3 * 60, "3 minutes"),
    ONE_MIN(1 * 60, "1 minute")
}

/**
 * Listener the Activity implements to update the screen and hear announcements.
 * All callbacks arrive on the main thread.
 */
interface SequenceListener {
    fun onPhaseChanged(phase: Phase, paused: Boolean)
    fun onConfigChanged()               // mode / prog additions changed - refresh mode label
    fun onTimeUpdated(displaySeconds: Int, counting: Boolean)
    fun onSpeak(text: String)          // queued countdown speech
    fun onAnnounce(text: String)       // immediate/interrupting speech
    fun onBeep()
}

class SequenceEngine(private val listener: SequenceListener) {

    private val handler = Handler(Looper.getMainLooper())

    var phase = Phase.STANDBY
        private set
    var isPaused = false
        private set

    var mode = SequenceMode.FIVE_MIN
        private set

    // How many extra "Prog" presses have been added on top of the base mode duration.
    private var progAddCount = 0

    private var remaining = mode.seconds   // seconds left until start, while phase == COUNTDOWN
    private var elapsed = 0                // seconds since start, while phase == COUNTUP

    private val tickRunnable = object : Runnable {
        override fun run() {
            if (isPaused) return
            when (phase) {
                Phase.COUNTDOWN -> {
                    tickCountdown()
                    if (!isPaused) handler.postDelayed(this, 1000)
                }
                Phase.COUNTUP -> {
                    elapsed++
                    listener.onTimeUpdated(elapsed, counting = true)
                    handler.postDelayed(this, 1000)
                }
                Phase.STANDBY -> { /* not ticking */ }
            }
        }
    }

    init {
        listener.onTimeUpdated(remaining, counting = false)
        listener.onConfigChanged()
    }

    // ---------------------------------------------------------------------
    // Button actions
    // ---------------------------------------------------------------------

    /** Short press: start, or pause/resume whatever is currently counting. */
    fun onStartStop() {
        when (phase) {
            Phase.STANDBY -> {
                remaining = currentDuration()
                isPaused = false
                phase = Phase.COUNTDOWN
                listener.onPhaseChanged(phase, isPaused)
                listener.onAnnounce("Start. " + describeWhole(remaining))
                listener.onTimeUpdated(remaining, counting = false)
                handler.postDelayed(tickRunnable, 1000)
            }
            Phase.COUNTDOWN, Phase.COUNTUP -> {
                if (isPaused) {
                    isPaused = false
                    listener.onPhaseChanged(phase, isPaused)
                    val timeText = if (phase == Phase.COUNTDOWN) describeWhole(remaining) else describeWhole(elapsed)
                    listener.onAnnounce("Resumed. $timeText")
                    handler.postDelayed(tickRunnable, 1000)
                } else {
                    isPaused = true
                    handler.removeCallbacks(tickRunnable)
                    listener.onPhaseChanged(phase, isPaused)
                    listener.onAnnounce("Paused.")
                }
            }
        }
    }

    /** Long press: fully stop and return to standby, discarding progress, from any phase. */
    fun onStartStopLongPress() {
        if (phase == Phase.STANDBY) return
        handler.removeCallbacks(tickRunnable)
        progAddCount = 0
        resetToStandby()
        listener.onConfigChanged()
        listener.onAnnounce("Reset. " + describeWhole(remaining))
    }

    /** Snaps down to the whole minute mark already passed - e.g. 4:55 remaining becomes 4:00. */
    fun onSync() {
        if (phase != Phase.COUNTDOWN) {
            listener.onAnnounce("Sync is only available while the countdown is running.")
            return
        }
        remaining = if (remaining > 0) {
            ((remaining - 1) / 60) * 60
        } else {
            0
        }
        listener.onTimeUpdated(remaining, counting = false)
        listener.onAnnounce("Synced. " + describeWhole(remaining))
    }

    /** Adds one more block of the current mode's length on top of the standby time. */
    fun onProg() {
        if (phase != Phase.STANDBY) {
            listener.onAnnounce("Stop the sequence before programming.")
            return
        }
        progAddCount++
        remaining = currentDuration()
        listener.onConfigChanged()
        listener.onAnnounce("Prog. " + describeWhole(remaining))
        listener.onTimeUpdated(remaining, counting = false)
    }

    fun onClear() {
        handler.removeCallbacks(tickRunnable)
        progAddCount = 0
        resetToStandby()
        listener.onConfigChanged()
        listener.onAnnounce("Cleared. " + mode.label + ".")
    }

    fun onMode() {
        if (phase != Phase.STANDBY) {
            listener.onAnnounce("Stop the sequence to change mode.")
            return
        }
        mode = when (mode) {
            SequenceMode.FIVE_MIN -> SequenceMode.THREE_MIN
            SequenceMode.THREE_MIN -> SequenceMode.ONE_MIN
            SequenceMode.ONE_MIN -> SequenceMode.FIVE_MIN
        }
        progAddCount = 0
        remaining = currentDuration()
        listener.onConfigChanged()
        listener.onAnnounce("Mode. ${mode.label}.")
        listener.onTimeUpdated(remaining, counting = false)
    }

    // ---------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------

    private fun currentDuration(): Int = mode.seconds * (1 + progAddCount)

    private fun resetToStandby() {
        remaining = currentDuration()
        elapsed = 0
        isPaused = false
        phase = Phase.STANDBY
        listener.onPhaseChanged(phase, isPaused)
        listener.onTimeUpdated(remaining, counting = false)
    }

    /**
     * Called once per second while counting down. Implements the announcement schedule:
     *
     *  R > 60  and R % 60 in 1..5   -> speak bare digit (5,4,3,2,1) - approach to a whole minute
     *  R > 60  and R % 60 == 0      -> speak "<n> minutes" - arriving at a whole minute mark
     *  R > 60  and R % 10 == 0      -> speak "<m> minutes <s> seconds" - general 10s heartbeat
     *  R in 31..60                  -> speak "<n> seconds" every 5 seconds
     *  R in 1..30                   -> speak bare number every second
     *  R == 0                       -> loud beep only, then switch to count-up
     */
    private fun tickCountdown() {
        remaining--
        val r = remaining

        if (r < 0) return

        if (r == 0) {
            listener.onBeep()
            elapsed = 0
            phase = Phase.COUNTUP
            listener.onPhaseChanged(phase, isPaused)
            listener.onTimeUpdated(elapsed, counting = true)
            handler.postDelayed(tickRunnable, 1000)
            return
        }

        listener.onTimeUpdated(r, counting = false)

        when {
            r > 60 -> {
                val rem60 = r % 60
                when {
                    rem60 in 1..5 -> listener.onSpeak(rem60.toString())
                    rem60 == 0 -> listener.onSpeak(describeWhole(r))
                    r % 10 == 0 -> listener.onSpeak(describeMinSec(r))
                }
            }
            r in 31..60 -> {
                if (r == 60) {
                    listener.onSpeak(describeWhole(r))
                } else if (r % 5 == 0) {
                    listener.onSpeak("$r seconds")
                }
            }
            r in 1..30 -> {
                listener.onSpeak(r.toString())
            }
        }
    }

    private fun describeWhole(totalSeconds: Int): String {
        val mins = totalSeconds / 60
        val secs = totalSeconds % 60
        val minPart = when (mins) {
            0 -> ""
            1 -> "1 minute"
            else -> "$mins minutes"
        }
        val secPart = if (secs == 0) "" else "$secs seconds"
        return listOf(minPart, secPart).filter { it.isNotEmpty() }.joinToString(" ")
    }

    private fun describeMinSec(totalSeconds: Int): String = describeWhole(totalSeconds)

    fun currentDisplaySeconds(): Int = if (phase == Phase.COUNTUP) elapsed else remaining

    fun currentModeSummary(): String =
        if (progAddCount > 0) "Mode: ${mode.label} +$progAddCount (${currentDuration() / 60} min)"
        else "Mode: ${mode.label}"

    fun teardown() {
        handler.removeCallbacksAndMessages(null)
    }
}

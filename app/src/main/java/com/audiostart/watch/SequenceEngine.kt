package com.audiostart.watch

import android.os.Handler
import android.os.Looper
import android.os.SystemClock

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
    fun onConfigChanged(modeSummary: String)
    fun onTimeUpdated(displaySeconds: Int, counting: Boolean)
    fun onSpeak(text: String)
    fun onAnnounce(text: String)
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

    // Number of extra "Prog" presses added on top of the base mode duration.
    private var progAddCount = 0

    private var remaining = mode.seconds
    private var elapsed = 0

    /*
     * Timing is based on absolute elapsedRealtime() values.
     *
     * The timer no longer assumes that every Handler callback occurs exactly
     * one second apart. If the main thread is delayed, the next callback
     * recalculates the correct value from these timestamps.
     */
    private var countdownEndRealtime = 0L
    private var countupStartRealtime = 0L

    private val tickRunnable = object : Runnable {
        override fun run() {
            if (isPaused) return

            val now = SystemClock.elapsedRealtime()

            when (phase) {
                Phase.COUNTDOWN -> {
                    val newRemaining =
                        ((countdownEndRealtime - now + 999L) / 1000L)
                            .toInt()
                            .coerceAtLeast(0)

                    if (newRemaining != remaining) {
                        remaining = newRemaining
                        tickCountdownDisplay()
                    }

                    if (remaining <= 0) {
                        triggerStartSignal()
                        scheduleNextTick(SystemClock.elapsedRealtime())
                    } else {
                        scheduleNextTick(now)
                    }
                }
                Phase.COUNTUP -> {
                    val newElapsed =
                        ((now - countupStartRealtime) / 1000L)
                            .toInt()
                            .coerceAtLeast(0)

                    if (newElapsed != elapsed) {
                        elapsed = newElapsed
                        listener.onTimeUpdated(elapsed, counting = true)
                    }

                    scheduleNextTick(now)
                }

                Phase.STANDBY -> {
                    // Nothing to do.
                }
            }
        }
    }

    init {
        listener.onTimeUpdated(remaining, counting = false)
        listener.onConfigChanged(currentModeSummary())
    }

    // ---------------------------------------------------------------------
    // Button actions
    // ---------------------------------------------------------------------

    /** Short press: start, or pause/resume whatever is currently counting. */
    fun onStartStop() {
        when (phase) {
            Phase.STANDBY -> {
                remaining = currentDuration()
                elapsed = 0
                isPaused = false
                phase = Phase.COUNTDOWN

                listener.onPhaseChanged(phase, isPaused)
                listener.onAnnounce("Start. " + describeWhole(remaining))
                listener.onTimeUpdated(remaining, counting = false)

                startFreshSecondTick()
            }

            Phase.COUNTDOWN, Phase.COUNTUP -> {
                if (isPaused) {
                    isPaused = false
                    listener.onPhaseChanged(phase, isPaused)
                    listener.onAnnounce("Resumed. " + currentTimeDescription())

                    resumeTickFromPaused()
                } else {
                    pauseTicking()
                    listener.onPhaseChanged(phase, isPaused)
                    listener.onAnnounce("Paused. " + currentTimeDescription())
                }
            }
        }
    }

    /** Long press: fully stop and return to standby. */
    fun onStartStopLongPress() {
        onReset()
    }

    /**
     * Snaps down to the whole minute mark already passed.
     *
     * For example, 4:55 remaining becomes 4:00.
     * If that lands exactly on zero, the start signal fires immediately.
     */
    fun onSync() {
        if (phase == Phase.COUNTUP) {
            onReset()
            return
        }

        if (phase != Phase.COUNTDOWN) {
            listener.onAnnounce("Sync is only available while the countdown is running.")
            return
        }

        // Make sure remaining reflects the real clock before syncing.
        if (!isPaused) {
            updateRemainingFromClock()
        }

        remaining = if (remaining > 0) {
            ((remaining - 1) / 60) * 60
        } else {
            0
        }

        handler.removeCallbacks(tickRunnable)

        if (remaining <= 0) {
            isPaused = false
            triggerStartSignal()
            scheduleNextTick(SystemClock.elapsedRealtime())
            return
        }

        listener.onTimeUpdated(remaining, counting = false)

        val wasPaused = isPaused

        if (wasPaused) {
            isPaused = false
            listener.onPhaseChanged(phase, false)
        }

        startFreshSecondTick()

        val announcement =
            if (wasPaused) "Synced and resumed. " else "Synced. "

        listener.onAnnounce(announcement + describeWhole(remaining))
    }

    /** Adds one more block of the current mode's length. */
    fun onProg() {
        if (phase != Phase.STANDBY) {
            listener.onAnnounce("Stop the sequence before programming.")
            return
        }

        progAddCount++
        remaining = currentDuration()

        listener.onConfigChanged(currentModeSummary())
        listener.onAnnounce("Prog. " + describeWhole(remaining))
        listener.onTimeUpdated(remaining, counting = false)
    }

    fun onClear() {
        handler.removeCallbacks(tickRunnable)

        progAddCount = 0
        resetToStandby()

        listener.onConfigChanged(currentModeSummary())
        listener.onAnnounce("Cleared. " + mode.label + ".")
    }

    fun onReset() {
        if (phase == Phase.STANDBY) return

        handler.removeCallbacks(tickRunnable)

        progAddCount = 0
        resetToStandby()

        listener.onConfigChanged(currentModeSummary())
        listener.onAnnounce("Reset. " + describeWhole(remaining))
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

        listener.onConfigChanged(currentModeSummary())
        listener.onAnnounce("Mode. ${mode.label}.")
        listener.onTimeUpdated(remaining, counting = false)
    }

    // ---------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------

    private fun currentDuration(): Int {
        return mode.seconds * (1 + progAddCount)
    }

    private fun currentTimeDescription(): String {
        return if (phase == Phase.COUNTDOWN) {
            describeWhole(remaining) + " remaining"
        } else {
            "Elapsed " + describeWhole(elapsed)
        }
    }

    private fun resetToStandby() {
        remaining = currentDuration()
        elapsed = 0
        isPaused = false
        countdownEndRealtime = 0L
        countupStartRealtime = 0L
        phase = Phase.STANDBY

        listener.onPhaseChanged(phase, isPaused)
        listener.onTimeUpdated(remaining, counting = false)
    }

    /**
     * Starts or restarts the timer using an absolute deadline.
     */
    private fun startFreshSecondTick() {
        handler.removeCallbacks(tickRunnable)

        countdownEndRealtime =
            SystemClock.elapsedRealtime() + remaining * 1000L

        handler.post(tickRunnable)
    }

    /**
     * Freezes the timer and calculates the current value before pausing.
     */
    private fun pauseTicking() {
        val now = SystemClock.elapsedRealtime()

        when (phase) {
            Phase.COUNTDOWN -> updateRemainingFromClock(now)

            Phase.COUNTUP -> {
                elapsed =
                    ((now - countupStartRealtime) / 1000L)
                        .toInt()
                        .coerceAtLeast(0)
            }

            Phase.STANDBY -> Unit
        }

        isPaused = true
        handler.removeCallbacks(tickRunnable)
    }

    /**
     * Resumes from the current whole-second value.
     *
     * The timer resumes with that value as its new reference point. This
     * avoids accumulating Handler delays while also keeping pause behavior
     * predictable at whole-second display resolution.
     */
    private fun resumeTickFromPaused() {
        val now = SystemClock.elapsedRealtime()

        when (phase) {
            Phase.COUNTDOWN -> {
                countdownEndRealtime = now + remaining * 1000L
            }

            Phase.COUNTUP -> {
                countupStartRealtime = now - elapsed * 1000L
            }

            Phase.STANDBY -> Unit
        }

        handler.post(tickRunnable)
    }

    /**
     * Updates remaining from the absolute countdown deadline.
     */
    private fun updateRemainingFromClock(
        now: Long = SystemClock.elapsedRealtime()
    ) {
        remaining =
            ((countdownEndRealtime - now + 999L) / 1000L)
                .toInt()
                .coerceAtLeast(0)
    }

    /**
     * Schedules the next update based on the real clock.
     *
     * A delayed callback may skip a display value, but it cannot make the
     * countdown itself run slower.
     */
    private fun scheduleNextTick(now: Long) {
        val delay = 1000L - (now % 1000L)
        handler.postDelayed(tickRunnable, delay.coerceAtLeast(1L))
    }

    /**
     * Fires the start signal and switches to count-up.
     * This method does not schedule a callback.
     */
    private fun triggerStartSignal() {
        listener.onBeep()

        elapsed = 0
        countupStartRealtime = SystemClock.elapsedRealtime()

        phase = Phase.COUNTUP

        listener.onPhaseChanged(phase, isPaused)
        listener.onTimeUpdated(elapsed, counting = true)
    }

    /**
     * Called when the displayed countdown value changes.
     */
    private fun tickCountdownDisplay() {
        val secondsRemaining = remaining

        listener.onTimeUpdated(secondsRemaining, counting = false)

        when {
            secondsRemaining > 60 -> {
                val remainingSeconds = secondsRemaining % 60
                val syncWarning = mode.seconds - 60

                when {
                    remainingSeconds in 1..5 &&
                            secondsRemaining in syncWarning + 1..syncWarning + 5 -> {
                        listener.onSpeak(remainingSeconds.toString())
                    }

                    remainingSeconds == 0 -> {
                        listener.onSpeak(describeWhole(secondsRemaining))
                    }

                    secondsRemaining % 10 == 0 -> {
                        listener.onSpeak(describeMinSec(secondsRemaining))
                    }
                }
            }

            secondsRemaining in 31..60 -> {
                if (secondsRemaining == 60) {
                    listener.onSpeak(describeWhole(secondsRemaining))
                } else if (secondsRemaining % 5 == 0) {
                    listener.onSpeak(secondsRemaining.toString())
                }
            }

            secondsRemaining in 1..30 -> {
                listener.onSpeak(secondsRemaining.toString())
            }
        }
    }

    private fun describeWhole(totalSeconds: Int): String {
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60

        return if (seconds == 0) {
            "$minutes ${if (minutes == 1) "minute" else "minutes"}"
        } else {
            "%d:%02d".format(minutes, seconds)
        }
    }

    private fun describeMinSec(totalSeconds: Int): String {
        return describeWhole(totalSeconds)
    }

    fun currentModeSummary(): String {
        return if (progAddCount > 0) {
            "Mode: ${mode.label} +$progAddCount (${currentDuration() / 60} min)"
        } else {
            "Mode: ${mode.label}"
        }
    }

    fun teardown() {
        handler.removeCallbacksAndMessages(null)
    }
}

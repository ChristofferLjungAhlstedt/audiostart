package com.audiostart.watch

import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity(), SequenceListener {

    private lateinit var speech: SpeechManager
    private lateinit var beep: BeepPlayer
    private lateinit var engine: SequenceEngine

    private lateinit var txtStatus: TextView
    private lateinit var txtMode: TextView

    private lateinit var txtPhaseLabel: TextView

    private lateinit var btnStartStop: Button
    private lateinit var btnSync: Button
    private lateinit var btnProg: Button
    private lateinit var btnClear: Button
    private lateinit var btnMode: Button

    // Layout weight the big time readout uses in standby vs. while a sequence is active
    // (fewer visible siblings + a bigger weight = it fills much more of the screen).
    private val statusWeightStandby = 1.1f
    private val statusWeightRunning = 4f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Views must be bound BEFORE the engine is created: the engine's constructor
        // immediately reports the initial time via the SequenceListener callbacks below.
        txtStatus = findViewById(R.id.txtStatus)
        txtMode = findViewById(R.id.txtMode)
        txtPhaseLabel = findViewById(R.id.txtPhaseLabel)

        btnStartStop = findViewById(R.id.btnStartStop)
        btnSync = findViewById(R.id.btnSync)
        btnProg = findViewById(R.id.btnProg)
        btnClear = findViewById(R.id.btnClear)
        btnMode = findViewById(R.id.btnMode)

        speech = SpeechManager(this)
        beep = BeepPlayer()

        // NOTE: SequenceEngine's constructor calls back into this Activity synchronously
        // (via SequenceListener, e.g. onConfigChanged -> updateModeLabel -> engine.xxx)
        // BEFORE the `engine =` assignment below has completed. Any listener callback
        // that touches `engine` must therefore guard against it being unset yet -- see
        // updateModeLabel(). This is why the explicit updateModeLabel() call at the end
        // of onCreate() is still needed: it's the "real" first update, run once engine
        // is actually assigned.
        engine = SequenceEngine(this)

        wireButton(btnStartStop, "") { engine.onStartStop() }
        btnStartStop.setOnLongClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            speech.announce("Reset")
            engine.onStartStopLongPress()
            true
        }
        wireButton(btnSync, "") { engine.onSync() }
        wireButton(btnProg, "") { engine.onProg() }
        wireButton(btnClear, "") { engine.onClear() }
        wireButton(btnMode, "") { engine.onMode() }

        applyLayoutForPhase(Phase.STANDBY, paused = false)
        updatePhaseIndicator(Phase.STANDBY, paused = false)
        updateModeLabel()

    }

    /**
     * Every button: gives haptic feedback, immediately (interrupting) speaks the button's
     * name so a blind user always knows what they just pressed, then runs the action.
     * The engine's own follow-up announcements are queued (not flushed) so they play
     * right after the button name instead of racing with it.
     */
    private fun wireButton(button: Button, spokenName: String, action: () -> Unit) {
        button.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            speech.announce(spokenName)
            action()
        }
        button.contentDescription = spokenName
    }

    private fun updateModeLabel() {
        // Guard: SequenceEngine's constructor can invoke onConfigChanged() (and thus this
        // method) before `engine = SequenceEngine(this)` in onCreate() has finished
        // assigning the field. Without this check that early call crashes with
        // UninitializedPropertyAccessException. Safe to no-op here: onCreate() calls
        // updateModeLabel() again right after engine is assigned.
        if (!::engine.isInitialized) return
        txtMode.text = engine.currentModeSummary()
    }

    private fun formatTime(totalSeconds: Int): String {
        val m = totalSeconds / 60
        val s = totalSeconds % 60
        return String.format("%d:%02d", m, s)
    }

    /**
     * Prog/Clear/Mode are only meaningful when nothing is actively counting, so while a
     * sequence is actively running (or counting up after the start) they're hidden and the
     * big readout expands to use the freed-up space. As soon as it's paused, this switches
     * back to the full "main screen" (all buttons visible) so there's always a way back to
     * standby via Clear, without losing the paused time.
     */
    private fun applyLayoutForPhase(phase: Phase, paused: Boolean) {
        val activelyCounting = phase != Phase.STANDBY && !paused
        val showMainScreen = !activelyCounting

        btnProg.visibility = if (showMainScreen) android.view.View.VISIBLE else android.view.View.GONE
        btnClear.visibility = if (showMainScreen) android.view.View.VISIBLE else android.view.View.GONE
        btnMode.visibility = if (showMainScreen) android.view.View.VISIBLE else android.view.View.GONE
        txtMode.visibility = if (showMainScreen) android.view.View.VISIBLE else android.view.View.GONE

        val params = txtStatus.layoutParams as LinearLayout.LayoutParams
        params.weight = if (activelyCounting) statusWeightRunning else statusWeightStandby
        txtStatus.layoutParams = params
    }

    // ---------------- SequenceListener ----------------

    override fun onPhaseChanged(phase: Phase, paused: Boolean) {
        runOnUiThread {
            applyLayoutForPhase(phase, paused)
            updatePhaseIndicator(phase, paused)
        }
    }

    override fun onConfigChanged(modeSummary: String) {
        runOnUiThread { txtMode.text = modeSummary }
    }

    private fun updatePhaseIndicator(phase: Phase, paused: Boolean) {
        val (labelRes, colorRes) = when (phase) {
            Phase.STANDBY -> R.string.phase_standby to R.color.text_white
            Phase.COUNTDOWN -> (if (paused) R.string.phase_countdown_paused else R.string.phase_countdown) to R.color.text_countdown
            Phase.COUNTUP -> (if (paused) R.string.phase_countup_paused else R.string.phase_countup) to R.color.text_countup
        }
        txtPhaseLabel.setText(labelRes)
        val color = androidx.core.content.ContextCompat.getColor(this, colorRes)
        txtPhaseLabel.setTextColor(color)
        txtStatus.setTextColor(color)
    }

    override fun onTimeUpdated(displaySeconds: Int, counting: Boolean) {
        runOnUiThread {
            txtStatus.text = formatTime(displaySeconds)
        }
    }

    override fun onSpeak(text: String) {
        runOnUiThread { speech.say(text) }
    }

    override fun onAnnounce(text: String) {
        // Queued (not flushed) so it plays right after the just-spoken button name.
        runOnUiThread { speech.say(text) }
    }

    override fun onBeep() {
        runOnUiThread { beep.playStartBeep() }
    }

    override fun onDestroy() {
        super.onDestroy()
        engine.teardown()
        speech.shutdown()
        beep.release()
    }
}
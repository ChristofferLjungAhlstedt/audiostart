# Blind Start Watch

An Android sailing race start-sequence timer, built for blind and visually
impaired sailors. It replicates the core functionality of an Optimum Time
start watch, with the whole interaction redesigned around sound and touch
instead of sight: five large, distinctly colored buttons, spoken feedback on
every action, and a countdown that calls out time remaining the way a
sighted sailor would read it off a normal start watch.

## Buttons

| Button | Color | Function |
|---|---|---|
| **Start/Stop** | Green | Starts the sequence. While it's running, pauses it; press again to resume exactly where it left off. Long-press fully resets back to standby from anywhere. |
| **Sync** | Blue | Snaps the countdown down to the last whole minute mark (e.g. 4:55 → 4:00) — press it on a committee-boat signal. If the countdown was paused, Sync also resumes it. |
| **Prog** | Orange | Adds one more block of the selected Mode's length on top of the current time. In 3-minute mode: press once for 6 minutes, again for 9, and so on. |
| **Clear** | Red | Cancels the current run and drops any Prog additions, returning to the plain Mode duration. |
| **Mode** | Purple | Cycles the base sequence length: 5 minutes → 3 minutes → 1 minute. Also clears any Prog additions. |

Every press is announced by name the instant you touch it ("Start Stop",
"Sync", ...), followed by whatever state change it caused, so you always know
both what you touched and what happened as a result. Buttons also carry
matching `contentDescription`s for TalkBack.

**Prog, Clear, and Mode only make sense before or between runs**, so they're
hidden while the countdown is actively counting — leaving just Start/Stop and
Sync, with the time display expanding to use the freed screen space. Pausing
brings all five buttons back (including Clear, your way back to standby
without losing the paused time); they hide again on resume.

## How the countdown sounds

- **More than 1 minute left:** announced every 10 seconds, e.g. "4 minutes
  50 seconds".
- **The 5 seconds before every whole-minute mark**, throughout the whole
  countdown: counts "5, 4, 3, 2, 1" then announces the new minute, e.g.
  "...2, 1, 4 minutes".
- **Inside the final minute (60 → 31 seconds):** every 5 seconds ("55
  seconds", "50 seconds", ...).
- **Final 30 seconds:** every single second, counting straight down.
- **At zero:** a loud beep instead of a spoken "0" or "start". The watch then
  silently switches to counting *up* (elapsed race time) until you stop it.

Pausing and resuming preserves sub-second timing — resuming waits out
whatever was left of the current second rather than restarting it, so no
time is gained or lost across a pause.

## Accessibility notes

- Five buttons, high-contrast distinct colors, large touch targets.
- Every action is spoken via Android's Text-to-Speech, with haptic feedback
  on every press.
- The time display uses auto-sizing, single-line text that scales to fill
  whatever space it's given — largest during an active countdown, when the
  other buttons are hidden.

## Getting started

This is a standard Android Studio (Kotlin) project.

1. Clone the repo and open it in Android Studio.
2. Let Gradle sync.
3. Build > Run, or from a terminal: `./gradlew assembleDebug`.

Package name: `com.blindstart.watch`. No special permissions are required
beyond `WAKE_LOCK` (keeps the screen on during a countdown) and a `<queries>`
declaration for `android.intent.action.TTS_SERVICE`, which Android requires
on API 30+ for the app to see installed text-to-speech engines at all.

### Text-to-speech setup

The app needs a working TTS engine and an installed voice to do anything
useful:

- **Samsung devices:** Samsung's built-in TTS engine can refuse to bind for
  third-party apps. If speech doesn't work out of the box, install **Speech
  Services by Google** from the Play Store and set it as the preferred
  engine under Settings → General management → Language and input →
  Text-to-speech output.
- If no engine is available at all, the app automatically opens Android's
  voice-data install screen on first launch.
- On an emulator, use a system image with Google APIs/Play Store — bare AOSP
  images typically ship no TTS engine.

## Possible future additions

- A settings screen for speech rate and the announcement cadence.
- Persisting the last-used mode/Prog additions across app restarts.
- Skipping the app's own button-name announcement when TalkBack is active,
  to avoid double-speech.
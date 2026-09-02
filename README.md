# Blind Start Watch

An Android sailing race start-sequence timer modeled on an Optimum Time watch,
redesigned for blind and visually impaired sailors: five large, high-contrast,
distinctly colored buttons and full spoken feedback via Android's built-in
Text-to-Speech engine.

This is a complete, ready-to-open **Android Studio (Kotlin) project** — not a
compiled app. Open the `BlindStartWatch` folder in Android Studio, let Gradle
sync, and run it on a device or emulator (Build > Run, or `./gradlew
assembleDebug` from a terminal with the Android SDK installed).

## Buttons

| Button | Color | Function |
|---|---|---|
| Start/Stop | Green | Starts the selected sequence; press again to stop and reset |
| Sync | Blue | While running, snaps the countdown to the nearest whole minute (press it on a committee-boat signal) |
| Prog | Orange | Programs a custom start-sequence length (see below) |
| Clear | Red | Cancels the current run and resets to the selected mode's full time |
| Mode | Purple | Cycles the standby sequence length: 5 min → 3 min → 1 min → 5 min... |

Every button press is announced by name first ("Start Stop", "Sync", etc.), so
you always know what you just touched, and it also sets `contentDescription`
so screen readers like TalkBack report the same thing if it's also enabled.

## Spoken countdown schedule (while running)

- **More than 1 minute left:** time announced every 10 seconds as "X minutes Y
  seconds".
- **5 seconds before every whole-minute mark** (throughout the whole
  countdown, not just near the start): counts "5, 4, 3, 2, 1" then announces
  the new minute value, e.g. "...2, 1, 4 minutes".
- **Inside the final minute (60 → 31 seconds):** announced every 5 seconds
  ("55 seconds", "50 seconds", ...).
- **Final 30 seconds:** announced every single second (bare numbers, "30",
  "29", "28"...).
- **At zero:** a loud beep (via `ToneGenerator` on the alarm stream) instead
  of a spoken "0" or "start", exactly as you asked. The watch then switches
  silently to counting *up* (elapsed race time) until Stop or Clear is
  pressed, matching how Optimum Time devices behave after the start.

## Changes in this revision (round 4)

- **Diagnosed further**: landing on the "Install Voice Data" screen (even
  with a language already showing installed) means the *default* engine
  binding itself is failing — a different problem than missing voice data.
  `SpeechManager` now:
  1. On failure, queries which TTS engine packages are actually visible to
     the app (via the `<queries>` entry) and logs them.
  2. If any are found, automatically retries once by naming that engine
     explicitly instead of asking Android to pick a "default" — this works
     around devices where no default engine is properly resolved even
     though a real engine with voice data is present, which fits what
     you're seeing.
- **If it still doesn't speak after this**, please grab Logcat filtered to
  tag `SpeechManager` right after launching the app — it will now print the
  exact list of TTS engine packages the app can see (or an empty list, which
  would mean the `<queries>` declaration isn't taking effect, e.g. a stale
  build) plus which engine it retried with. That output will tell us exactly
  what to fix next rather than guessing further.

## Changes in this revision (round 3)

- **Fixed the real cause of "TTS engine could not start" on real devices**:
  Android 11+ (API 30+) hides other apps' services — including every TTS
  engine — from an app unless it explicitly declares it wants to see them,
  via a `<queries>` block in the manifest. Without it, `TextToSpeech()` fails
  to bind to *any* engine even when the phone has one working perfectly well
  (e.g. Samsung's own TTS, or Speech Services by Google) — which is exactly
  the "works nowhere obvious why" symptom you'd get testing on a real
  Samsung phone. `AndroidManifest.xml` now declares:
  ```xml
  <queries>
      <intent>
          <action android:name="android.intent.action.TTS_SERVICE" />
      </intent>
  </queries>
  ```
  This should resolve it on its own. If it's still silent afterwards, it's
  worth confirming a voice is actually installed: Settings > General
  management > Language and input > Text-to-speech output > tap the ⚙ next
  to your engine > install/select a language voice, and use the "Play"
  button there — that test bypasses this app entirely and tells you whether
  it's a system-level TTS config issue.

## Changes in round 2

- **Sync now snaps down to the whole minute already passed**, not to the
  nearest one: at 4:55 remaining, Sync now sets it to 4:00 (previously it
  rounded to the nearest minute, which would have gone to 5:00).
- **"Back to the main screen" while paused.** Prog/Clear/Mode now hide only
  while a sequence is *actively counting* (not paused). As soon as you pause
  (Start/Stop), all five buttons reappear — including Clear, so there's
  always a way back to standby without losing the paused time. They hide
  again the moment you resume.
- **The countdown text is now as large as the screen allows.** It uses
  Android's auto-sizing TextView instead of a fixed font size, so it scales
  up to fill whatever space it's given — and that space itself grows a lot
  once the other buttons are hidden during an active countdown.
- **Fixed the mode label not updating.** `Mode: 5 min` was stuck because
  changing Mode never told the screen to refresh — only pausing/resuming
  did. Mode, Prog, and Clear now all explicitly refresh the label.
- **TTS init failure is now actionable.** If you saw "TTS engine could not
  start" that means Android reported no usable text-to-speech engine at all
  — not a bug in the app's TTS setup, but the device having no TTS engine
  installed/selected or no voice data downloaded. The app now automatically
  opens Android's own "install voice data" screen when this happens (the
  same one under Settings > System > Languages & input > Text-to-speech).
  Common fixes if that screen doesn't resolve it: make sure Google
  Text-to-Speech (or another TTS engine) is installed from the Play Store,
  set as the preferred engine, and has a voice downloaded for your language;
  on an emulator, use a system image that includes Google Play/Google APIs,
  since bare AOSP images often ship no TTS engine at all.

## Changes in the previous revision

- **Fixed a real bug:** views are now bound with `findViewById` *before* the
  engine is constructed. The engine's constructor immediately reports the
  starting time through the listener, so creating it first could touch
  `lateinit` views before they existed.
- **Start/Stop now pauses, not resets.** Press once to start; press again to
  pause (time freezes, nothing is lost); press again to resume. This works
  both during the countdown and during the post-start count-up. **Long-press
  Start/Stop** to fully reset back to standby from any active phase — since
  Prog/Clear/Mode are hidden once a sequence is active (see below), this is
  now the way to abort early instead of waiting for the countdown to reach
  zero.
- **Prog now adds time instead of setting it.** Each press adds one more
  block of the currently selected Mode's length on top of whatever's showing:
  3 min mode, press Prog → 6 min; press again → 9 min, and so on. **Clear**
  discards all of that and returns to the plain Mode duration (3 min in that
  example). Changing **Mode** also clears any Prog additions, since they'd no
  longer make sense against a different base length.
- **Start now always announces the starting time**, e.g. "Start. 9 minutes."
  — it reads back whatever the countdown was actually set to (base mode plus
  any Prog additions), not just the mode name.
- **Once a sequence is started** (running, paused, or counting up after the
  start), **Prog, Clear, and Mode are hidden**, leaving only Start/Stop and
  Sync — and the big time readout expands to take up most of the freed-up
  screen space.
- **Fixed the "no sound at all" issue**, most likely caused by one or both of:
  - Text-to-Speech had no installed voice data for the device's default
    language. The app now falls back to English automatically if that
    happens, and shows a one-time on-screen message if TTS truly can't be
    used, so a silent failure isn't invisible anymore.
  - The start beep was on the **alarm** audio stream, which has its own
    volume slider separate from media volume — if that happened to be muted
    on your device, the beep would be silent even though everything else
    worked. It now uses the **music** stream (same as the spoken
    announcements and the volume rocker), which is far less likely to
    surprise you. If you specifically want the louder/alarm-stream behavior
    back, it's a one-line swap in `BeepPlayer.kt` (commented there).
  - If you still get no sound after this, check: device isn't in silent/DND
    mode, media volume isn't at zero, and a TTS engine (Settings > System >
    Languages & input > Text-to-speech) is installed and set with a voice for
    your language.

## Design decisions / assumptions I made

A couple of behaviors weren't fully specified, so I made reasonable
Optimum-Time-style choices — flag these if you want them changed:

1. **Long-press Start/Stop = full reset.** Because Clear is hidden while a
   sequence is active, and Start/Stop now pauses instead of resetting, there
   needed to be *some* way to abort a countdown early without waiting for it
   to hit zero. A long-press does that from any active phase.
2. **Sync** only does something while the countdown is actively running
   (matches your answer) — pressing it in standby, while paused... actually
   Sync still works while paused (it just adjusts the frozen `remaining`
   value), but not in standby or after the start signal, where it announces
   that it isn't available rather than silently doing nothing.
3. **Mode** can only be changed in standby (matches real Optimum Time
   behavior — you shouldn't change the sequence length mid-countdown). It
   announces why if pressed while running.
4. **Whole-minute announcements** say "4 minutes" (not just the bare digit
   "4") so it's unambiguous that a minute mark was just reached, as opposed
   to the bare digits used in the 5-4-3-2-1 approach and in the final
   30-second countdown.
5. The screen still shows a large `mm:ss` readout and mode label — useful
   for low-vision users, a sighted crewmate glancing over, or just visually
   confirming the app is running correctly. Both are hidden/enlarged
   appropriately once a sequence is active (see "Changes in this revision").

## Things you may want to add later

- A settings screen to change the 2.5s Prog timeout, speech rate, or the
  10s/5s/1s cadence.
- Persisting the last-used mode/custom length across app restarts.
- An audible "ready" cue when Prog auto-confirms is currently the same voice
  used everywhere; you could give it a distinct earcon instead.
- If you want it, TalkBack-specific double-speech can be avoided by checking
  `AccessibilityManager.isTouchExplorationEnabled()` and skipping the app's
  own button-name announcement when TalkBack would already say it.

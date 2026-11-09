# H-ECU Synth: notes for Claude

Android app (Kotlin, no libraries) that makes a synthetic engine sound from the bike's rpm and
throttle. Sister app of H-ECU Logger (`..\Honda-ECU-Logger`, github.com/FACEPOT40/Honda-ECU-Logger).
Owner: Pai (IT support, learning; explain changes briefly and plainly). License GPL-3.0.
Started 9 Nov 2026. Name chosen by Pai ("H-ECU Synth": still fits if CAN bus is added later).
Package `com.pai.hecusynth` (never change it: that would make a separate app).

## Pai's choices (9 Nov)
- Just for fun while riding; Bluetooth helmet headset is fine, its delay (~150-300 ms) is
  accepted for now ("fix later").
- Styles: 1-cyl, V-twin, I4 superbike, F1 V6/V8/V10 (+ I4 crossplane added). Cam crossover at a
  chosen rpm ("VTEC" idea; called "Cam crossover" in the app: VTEC is a Honda trademark).
- Own app, separate repo (this folder). Not on GitHub yet: ask Pai before creating the remote.
- No recordings: everything is procedural.
- Simulator first, so sounds can be tried without the bike.

## Hard rules (when the K-line link is added)
- **Read-only, same as H-ECU Logger**: only wake (`FE 04 72 8C`), init (`72 05 00 F0 99`) and
  table queries (`72 05 71 <table> <cs>`), each 5 bytes with a correct checksum. Copy
  `Protocol.kt` / `KLine.kt` from the logger with its whitelist. Never write / flash / clear codes.
- Only one app can use the cable at a time, so the synth can't run next to the logger.
- No Honda branding (wing, logos); the About says "not affiliated with Honda".

## Layout
- `core/src/main/kotlin/com/pai/hecusynth/`: the sound engine, plain Kotlin, shared by the phone
  (`app/build.gradle.kts` adds it with `kotlin.srcDir`: AGP 9's built-in Kotlin ignores
  `java.srcDir` for this) and the desktop app. Edit it once, both get it.
- `app/`: Android app. `desktop/`: PC app (Kotlin/JVM 2.2.10 + Swing, no libraries).

## Files (core/ = Engines, Synth, EngineSim, Tuning; app/ = the rest)
- `Engines.kt`: `EngineStyle` (firing angles in the 720° cycle + sound parameters), `Styles.ALL`,
  `BikeRange` (bike idle/redline -> style idle/redline), `CamSwitch` (300 rpm hysteresis).
- Vacuum-cleaner fix (9 Nov, Pai: V6/V8/V10 "sound like a vacuum cleaner"): even-firing
  engines with identical cylinders give one pure whine. Each cylinder now gets fixed loudness /
  timing / pulse-length differences (`unevenAmp`, `unevenDeg`, `unevenWidth`) and the sound
  swells once per 720° cycle (`growl`, after the saturation, which would flatten it).
  `SynthTest.toneReport` prints growl/fire (cycle harmonics vs firing harmonics; F1 went from
  0.00 to 0.2-0.35) and hiss above 2 kHz. Not yet confirmed by ear.
- `Synth.kt`: the sound, pure Kotlin. Crank -> per-cylinder exhaust pulse + combustion noise ->
  pipe echo (comb) -> tone filter (`Svf`, opens with throttle and cam) -> intake roar, turbo
  (V6), pops (fuel cut at high rpm, ~12/s; OFF by default, Pai 9 Nov: "too much pop pop pop",
  the "Exhaust pops" switch turns them on) -> tanh saturation -> DC block -> soft limit.
  Input `set(bikeRpm, tps, fuelCut)` from any thread; rpm glides over the time since the last
  reading (12-150 ms) so 17/s K-line steps don't zipper.
- Rev limiter (9 Nov, Pai: "should be tatatata, it's ta... ta... ta"): the synth makes the
  stutter itself, since 17 K-line readings a second can't show a 20-40/s bounce. Active when
  bike rpm >= `BikeRange.redline` - 250 with throttle > 30 % (on the bike this needs the real
  limiter rpm in BikeRange, still fixed at 9500). Cuts whole firings, ~45 % of each pattern,
  pattern length a whole number of engine cycles (`limiterFirings` / `limiterCut`) so it
  can't drift against the bangs or cylinders; cut bangs at 0.03 (coasting cut stays 0.12);
  revs sag 2 % per cut. Rate per style `limiterHz` (18-38/s), on a tuning slider.
  `SynthTest.limiterStuttersFast` checks every style at K-line rate. Screens show "LIMITER".
- Lope, pops, bangs (9 Nov, Pai: max idle lump "not uneven enough like a lopey big-cam
  V-twin", pops "too dry", "add a bang sound to enable"): style `lope` (0..1, "Cam lope"
  slider; single 0.2, V-twin 0.35, I4 CP 0.1) at idle only (`idleF`, gone from 35 % of the rev
  range): rpm hunts up to 15 % (new random target every 0.15-0.5 s), +40° timing scatter,
  bang loudness +-70 %, 18 % weak bangs, more bark. "Idle lump" range now 0-40°.
  `Synth.bangs` (switch "Backfire bangs", off by default, desktop + phone).
- Pops and bangs v2 (9 Nov, matched to a reference video Pai sent, `Downloadsideoplayback.mp4`,
  never committed or used as audio; measured via Windows' MediaTranscoder -> WAV + numpy):
  the clip's pops come in BURSTS of 4-9, 25-30 ms apart, fading (+22 -> +12 dB over the
  engine), ringing 200 -> 30 ms, a "pap" at the pipe's notes (~300/480/660/900 Hz; power ~55 %
  200-500 Hz, ~27 % 500-1k, ~15 % 1-2k), edge around 1.2-2.5 kHz, almost no bass. Our old
  low "pof" / 60-80 Hz boom were the opposite and are gone.
  v2 rang on long, clean resonators with the same notes every pop: Pai, "sounds like a PVC
  instrument". Measured (spectral flatness 150-4000 Hz, 1 = noise): clip pops 0.034 / 0.024
  just after, v2 0.030 / 0.010 (too pure a ring). Pure noise tries gave hiss (0.18-0.39) or a
  dull whump (96 % under 200 Hz); the engine's own pipe comb puts it at 625/1250 Hz instead.
  v3 (short, per-pop-detuned resonators + wide filters) measured close to the clip on level,
  flatness and spectrum, but Pai: "still sounds like RimbaTubes". Lesson: struck pitched
  resonators read as a tube instrument whatever the ring time, and the numbers didn't catch it;
  let Pai judge by ear and offer choices.
  v4 (now, `Backfire.kt`, no pitched parts at all): same burst timing; `PopSound` picked by
  ear (desktop drop-down "Pop sound", phone button, pref `pop_sound`):
  BARK (default) = `takeBark()` turns the next fuel-cut firing into a 2.5x pulse, 1.6x longer,
  with 8 ms combustion noise, while the tone filter opens (+3000 Hz x `open`) and rasp rises
  (x 1 + open): the engine itself barks; plus a small shock click. CRACK = shock wave (0.4 ms up,
  -0.6 by 1.6 ms, 0 by 3.6 ms) + 5 ms noise burst 150 Hz-3.5 kHz, soft-clipped. Both: dark noise
  air tail 50 ms (bang 140 ms). Probe measure: bark +15 dB, mostly < 200 Hz, 20 ms decay; crack
  +10 dB, broadband; tail flatness 0.06 / 0.20 (v2 "PVC" was 0.010). Not yet heard by Pai.
  Measuring scripts were throwaway (scratchpad).
  Demo WAVs: `_pops`, `_bangs`, `vtwin_lope_max`, `single_lope_max`.
- `EngineSim.kt`: pretend engine revving in neutral (start/crank, inertia, limiter bounce,
  fuel cut on overrun). Gives the same three inputs the K-line will.
- `AudioOut.kt`: AudioTrack float mono at the native rate, 256-frame blocks, low-latency mode,
  USAGE_MEDIA (so it goes to a Bluetooth headset).
- `MainActivity.kt`: simulator screen (style buttons, rev meter, Start/Stop engine, volume,
  cam crossover switch + rpm, exhaust pops, "Bike data rate (17/s)"), throttle on the right.
  Settings in SharedPreferences "synth". Audio runs only while the screen is open.
- `Tuning.kt`: every tunable style setting (label, help text, range, get / copy-set),
  `toKotlin()` (the "Copy as Kotlin" text to paste over a style in `Styles.ALL`), save / load.
  `EngineStyle` is a data class for this. `Synth` keeps each cylinder's random character as
  -1..1 units and scales them by the style every block, so a slider moves the sound live
  without restarting the engine (`TuningTest.liveChangeDoesNotClick`).
- `desktop/.../Main.kt` (9 Nov, Pai: "make a desktop app first, easier to test"): style
  buttons, rev meter + throttle bar (mouse drag), Start/Stop (Enter), volume, cam, pops, bike
  data rate, Record WAV; tuning panel on the right (one slider per `Tuning.PARAMS`, help under
  each), "Copy as Kotlin", "Reset this style". Keys: hold Space/Up = 100 %, 1..9 = 10..90 %,
  0 = 100 %. Tuned values persist in `%USERPROFILE%\.hecusynth\tuning.properties` (desktop only;
  the phone gets them when pasted into Engines.kt). `DesktopAudio.kt`: javax.sound 16-bit
  48 kHz, ~40 ms buffer. Run: `run_desktop.bat` (= `gradlew :desktop:run`). Seen working 9 Nov
  (screenshot + Pai revving it).
- `Views.kt`: `RevMeterView`, `ThrottleView`. `Ui.kt`: same look/colors as the logger.
- Icon: the logger's italic H with an amber sound wave instead of the bolt.

## Build / test
`gradlew.bat testDebugUnitTest assembleDebug` with `JAVA_HOME` = Android Studio's `jbr`.
`SynthTest` checks silence with the engine off, no NaN / out of range, pitch locked to the
crank for every style, throttle makes it louder; and writes `app/build/demo-wav/<style>.wav`
(start, idle, blip, flat out into the limiter, let go, stop) to listen to on the PC, plus
`<style>_pops.wav` with the pops on.
Debug APK copied to `dist/` (gitignored).

## Status
- 0.1.0 (9 Nov): simulator built and unit-tested; not yet heard on the phone, sounds untuned
  by ear. BikeRange is fixed at idle 1400 / redline 9500 (no setting yet).
- 9 Nov: Pai likes the I4; the others sound "too synthetic". Tuning by ear in the desktop app
  is the next step; if tuning can't fix a style, next ideas: per-cylinder header lengths
  (better exhaust model), or Pai's own Wave recording as the single's base.
- Next: tune by ear with Pai, bike rev range setting, then K-line source (foreground service
  for screen-off, read 0x17 only for rpm / TPS / injector (inj 0 = fuel cut)).

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

## Files (app/src/main/java/com/pai/hecusynth)
- `Engines.kt`: `EngineStyle` (firing angles in the 720° cycle + sound parameters), `Styles.ALL`,
  `BikeRange` (bike idle/redline -> style idle/redline), `CamSwitch` (300 rpm hysteresis).
- `Synth.kt`: the sound, pure Kotlin. Crank -> per-cylinder exhaust pulse + combustion noise ->
  pipe echo (comb) -> tone filter (`Svf`, opens with throttle and cam) -> intake roar, turbo
  (V6), pops (fuel cut at high rpm, ~12/s) -> tanh saturation -> DC block -> soft limit.
  Input `set(bikeRpm, tps, fuelCut)` from any thread; rpm glides over the time since the last
  reading (12-150 ms) so 17/s K-line steps don't zipper.
- `EngineSim.kt`: pretend engine revving in neutral (start/crank, inertia, limiter bounce,
  fuel cut on overrun). Gives the same three inputs the K-line will.
- `AudioOut.kt`: AudioTrack float mono at the native rate, 256-frame blocks, low-latency mode,
  USAGE_MEDIA (so it goes to a Bluetooth headset).
- `MainActivity.kt`: simulator screen (style buttons, rev meter, Start/Stop engine, volume,
  cam crossover switch + rpm, exhaust pops, "Bike data rate (17/s)"), throttle on the right.
  Settings in SharedPreferences "synth". Audio runs only while the screen is open.
- `Views.kt`: `RevMeterView`, `ThrottleView`. `Ui.kt`: same look/colors as the logger.
- Icon: the logger's italic H with an amber sound wave instead of the bolt.

## Build / test
`gradlew.bat testDebugUnitTest assembleDebug` with `JAVA_HOME` = Android Studio's `jbr`.
`SynthTest` checks silence with the engine off, no NaN / out of range, pitch locked to the
crank for every style, throttle makes it louder; and writes `app/build/demo-wav/<style>.wav`
(start, idle, blip, flat out into the limiter, let go, stop) to listen to on the PC.
Debug APK copied to `dist/` (gitignored).

## Status
- 0.1.0 (9 Nov): simulator built and unit-tested; not yet heard on the phone, sounds untuned
  by ear. BikeRange is fixed at idle 1400 / redline 9500 (no setting yet).
- Next: tune by ear with Pai, bike rev range setting, then K-line source (foreground service
  for screen-off, read 0x17 only for rpm / TPS / injector (inj 0 = fuel cut)).

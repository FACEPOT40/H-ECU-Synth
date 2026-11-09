# H-ECU Synth

Synthetic engine sound for Android, driven by your bike's revs and throttle. Sister app of
[H-ECU Logger](https://github.com/FACEPOT40/Honda-ECU-Logger).

Every sound is made from scratch while you ride (no recordings): a virtual crankshaft fires
each cylinder at the right angle, and the exhaust pulses are shaped by a pipe echo and a tone
filter that opens with the throttle.

**Status: 0.1.0.** Simulator (rev with the on-screen throttle) and Bike mode: connects to the
ECU with the same FTDI K-line cable as H-ECU Logger and plays from the real rpm and throttle,
with the screen off too. Bike mode is built and tested with a fake ECU, not yet on the bike.

The K-line link is **read-only**: it only sends wake-up, init and live-data queries, never
anything that writes to the ECU.

## Download (v1.0.0)
From this repo's **Releases** page:
- **H-ECU-Synth-1.0.0.apk**: the phone app (Android 10+). If you had a test build installed,
  uninstall it first (a release build is signed differently, Android won't update over it).
- **H-ECU-Synth-Desktop-1.0.0-win64.zip**: the desktop simulator and sound tuner for Windows.
  Unzip and run `H-ECU Synth.exe` (Java is included).

On the bike it needs the same FTDI K-line cable as H-ECU Logger (USB OTG). Close H-ECU Logger
first: only one app can use the cable.

## Engine styles
1-cyl · 90° V-twin · I4 superbike (flat-plane) · I4 crossplane · V6 turbo · V8 · V10

The bike's idle and redline are mapped onto each style's own range, so a 9,500 rpm bike can
sound like a V10 at 19,000.

Also: cam crossover at a chosen rpm (harder tone above it), exhaust pops when you let go,
and a "bike data rate" switch that feeds the sound at K-line speed (~17 readings a second).

## Desktop app (try and tune the sounds on the PC)
`run_desktop.bat`: the same sound engine as the phone, with a slider for every sound setting.
Hold Space to rev, Enter starts / stops the engine. "Copy as Kotlin" copies a tuned style.

## Build
```
gradlew.bat testDebugUnitTest assembleDebug
build_apk_release.bat        # dist\H-ECU-Synth-<version>.apk (signed)
build_desktop_release.bat    # dist\H-ECU-Synth-Desktop-<version>-win64.zip
```
with `JAVA_HOME` set to Android Studio's `jbr`. The tests also write one demo rev run per style
to `app/build/demo-wav/` so you can listen on the PC.

## Safety
Keep the volume low enough to hear traffic. The K-line link will only read data, never write
to the ECU (same whitelist as H-ECU Logger).

## License
GPL-3.0. Not affiliated with or endorsed by Honda. Engine names describe engine layouts only.

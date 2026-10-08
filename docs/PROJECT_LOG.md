# HF Digital Modes: project log

Newest first.

## 2026-10-08: 0.1.0, stage 1 (skeleton)

- New project `Documents\AndroidStudioProjects\HamDigital`, package `uk.hamdigital`, app name "HF Digital Modes".
  It uses the same build setup as HF Propagation (Gradle 9.8, AGP 9.4.1, Kotlin 2.4.20, compileSdk 37, NDK 30, CMake 4.1.2).
- Decisions and the stage plan are in `docs/PLAN.md`: modes, USB first, receive first, GPL v3, and which open-source code
  each mode uses.
- Look: HF Propagation's `Theme.kt` is copied (palette, Orbitron, SmallChip, CompactField). The startup screen has HF
  Propagation's layout, with an animated waterfall drawn in code showing each mode's signal shape.
- Menu: a tile per mode (decoder ready, or "Waterfall now" with the stage that brings its decoder), the radio's USB
  connection status, Settings, and Guide. Back returns to the menu.
- Receive audio (`audio/AudioIn.kt`) comes from the IC-705's USB sound card when it is plugged in (`setPreferredDevice`
  to the USB input), otherwise from the microphone. The input is the unprocessed source where the phone offers it. The
  page that started the capture owns it, so a closing page can't stop the next page's audio.
- Waterfall (`audio/Spectrum.kt` + `ModeFrame.kt`): a 2048-point Hann FFT, 0–3000 Hz, scaled to the noise floor, drawn
  through a bitmap. Every mode page has one, plus a UTC clock and an audio source and level line.
- CW: the shared HamPropCore decoder through `cw_jni.cpp` (from HF Propagation). The page is HF Propagation's CW tool,
  with Pause/Listen in place of Start/Stop because the page owns the audio.
- The other modes' pages show the waterfall, the mode's band chips and dial frequency, and which stage brings their decoder.
- Settings: callsign, locator (checked), receive audio source, startup screen, About (licence and credits). The Guide
  has a topic per screen and mode, plus a version history.
- Upstream sources were cloned for reference into `_upstream`: ft8_lib, FT8CN, js8call, fldigi (only the needed
  folders checked out, because the full checkout fails on Windows), WSJT-X and usb-serial-for-android.
- `gradlew assembleDebug` builds. Not tested on a device yet, because none was connected.

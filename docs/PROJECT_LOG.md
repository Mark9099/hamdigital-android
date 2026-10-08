# HF Digital Modes: project log

Newest first.

## 2026-10-08: 0.2.0, stage 2 (IC-705 CI-V over USB)

- `rig/Ic705.kt`: CI-V on the IC-705's first USB serial port (CDC-ACM, using usb-serial-for-android 3.11.0 from JitPack,
  MIT). It asks for USB permission (a mutable PendingIntent tied to the package), then opens the port with DTR on and
  RTS kept low, because the IC-705 can key on RTS. A reader thread collects frames and polls frequency (03), mode (04)
  and data mode (1A 06) every second. It also takes the radio's transceive broadcasts (00/01), ignores echoed frames
  from E0, and notices when the lead is unplugged.
- Commands: set frequency (05, 5-byte BCD), set mode (06) to USB + DATA (1A 06 01 01) or CW + DATA off, and PTT (1C 00)
  ready for stage 7.
- `ui/RigBar.kt` on every mode page shows the radio's frequency and mode, with "(USB-D needed)" when the mode is wrong
  and TX while transmitting. Band chips tune the radio. Without a radio, the chips show the frequency to tune by hand.
- The menu shows audio and control status with the frequency. Settings has a Radio control section: status, CI-V
  address (hex, default A4) and Reconnect.
- Manifest: the activity takes USB_DEVICE_ATTACHED for Icom (vendor 0x0C26, IC-705 0x0036), so Android offers to open
  the app and can remember the permission. Connecting happens on resume and when the radio is plugged in.
- Guide updated (IC-705 connection, menu, version history). Builds. Not yet tried with the radio.

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

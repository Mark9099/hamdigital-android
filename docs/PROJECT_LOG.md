# HF Digital Modes: project log

Newest first.

## 2026-10-09: 0.4.0, stage 4 (WSPR receive)

- wsprd from WSJT-X (commit 2b9d654, GPL v3) is copied to `cpp/wsprd`: `wsprd.c`, the utils, `fano`, `jelinek`,
  `nhash`, `tab`, and `metric_tables.c` (which `wsprd.c` includes). `wsprd.c`'s `main()` is compiled as `wsprd_main`.
  The OSD decoder (`osdwspr.f90`, Fortran) is only used with `-o`, which the app never passes, so `wspr_run.c` has a
  stub for it.
- **No FFTW build:** `cpp/fftw_kiss/` implements the FFTW calls wsprd (and JS8Call's decoder) use on top of KISS FFT
  from ft8_lib: c2c and in-place r2c, unnormalised, with no-op wisdom functions. KISS handles all their sizes
  (factors 2, 3, 5): wsprd uses 1474560, 46080 and 512, and JS8 uses 180000.
- `wspr_run.c` runs wsprd the way WSJT-X does (`-a <data dir> -f <dial MHz> yymmdd_hhmm.wav`) and returns
  `wspr_spots.txt`. Before each run it resets getopt (`optind = 1`, plus `optreset` on Android) and holds a mutex,
  because wsprd uses globals.
- `core/WsprDecoder.kt` keeps a 125 s ring and the UTC time of the newest sample. At 1:54 after each even minute it
  writes the slot's 114 s as a WAV in the cache, named the way WSJT-X names them (wsprd takes the date and time from
  the name), and runs wsprd on a worker thread. The dial comes from the IC-705 over CI-V; with no radio connected,
  frequencies show as audio Hz. The WAV is deleted afterwards. The spots (call, locator, dBm in W/mW, drift, km) are kept.
- `ui/WsprScreen.kt`: the radio bar, a 2-minute slot bar, a waterfall of 1400–1600 Hz at 1.5 Hz resolution (8192-point
  FFT), and the spot list. `Spectrum` now takes a `minHz` and the waterfall scale follows it.
- PC test (`tools/test/run_wspr.sh`): WSJT-X's `samples/WSPR/150426_0918.wav` gives 9 spots (ND6P, W5BIT, G8VDQ, WD4LHT,
  NM7J, KI7CI, DJ6OL, W3HH, W3BI). Three runs in one process give the same spots each time.
- Also checked: WSJT-X's real FT4 sample decodes 7 messages (contest CQs, reports, RR73), and its FT8 sample 8.
- Not yet tested on the phone with the radio.

## 2026-10-09: 0.3.0, stage 3 (FT8 and FT4 receive)

- ft8_lib (commit 9fec6ca, MIT) is copied to `cpp/ft8_lib`. `cpp/ft8_slot.c` decodes a slot the way ft8_lib's demo does
  (monitor, candidates, LDPC, unpack, dropping duplicates, callsign hash table kept between slots) and has no JNI code,
  so the PC test compiles the same file. `ft8_jni.c` is the thin JNI wrapper.
- SNR: signal power at the message's own tones (re-encoded), against the median noise in about ±60 Hz beside it over
  the slot, converted to 2500 Hz. Against WSJT-X on ft8_lib's 31 test recordings, signals of -15 dB and up average
  -0.1 dB different, rms 4.7 dB. The weakest signals scatter more, because overlapping signals add to their tone power.
- DT: ft8_lib's times are one symbol late, so the correction is t - 0.5 - symbol period. It matches WSJT-X to rms
  0.04 s, and generated FT8 and FT4 signals come out within 0.03 s.
- **ft8_lib fix (FT4):** the start-time search covered -10 to 20 symbols for both modes, which is only -0.48 to +0.96 s
  for FT4. It is now -34 to 67 symbols for FT4, the same time span as FT8. Upstream's own decoder could not decode
  upstream's own generated FT4 signal; now it decodes. Recorded in `ft8_lib/ANDROID_CHANGES.txt`.
- Decode rate: 266 of WSJT-X's 362 messages (73%) on the test set, typical for ft8_lib. More candidates, a lower
  sync threshold or more LDPC iterations each added at most one message, so ft8_lib's defaults are kept. The gap is
  WSJT-X's signal-subtraction passes.
- `core/SlotDecoder.kt` keeps a 30 s ring of 12 kHz audio and the UTC time of the newest sample. At 14.7 s into each
  FT8 slot (7.3 s for FT4) it copies that slot's audio, filling with silence if the audio started late, and decodes it
  on a single worker thread. It parses the calls, the locator (RR73 excluded), CQ and "to me", and the distance from
  your locator (`core/Locator.kt`). Up to 600 messages are kept while the app runs.
- `ui/Ft8Screen.kt` (FT8 and FT4): the radio bar, a slot progress bar with the decode status, a waterfall (beside the
  list on a phone held sideways), All / CQ / To me filters, and the list (UTC, dB, DT, Hz, message, km) with CQ in
  green and "to me" in amber.
- PC test: `tools/test/test_ft8.c` and `run_ft8.sh`, built with zig cc. `win_compat.h` supplies `stpcpy`, which
  Windows lacks.
- Not yet tested on the phone with the radio.

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

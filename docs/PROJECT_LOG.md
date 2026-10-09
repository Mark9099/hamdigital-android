# HF Digital Modes: project log

Newest first.

## 2026-10-09: 0.7.2, stage 7c (RTTY, PSK31 and CW sending)

- `cpp/fldigi/kb_tx.cxx` builds the transmit audio for a whole message, following fldigi's transmitters.
  - PSK31: a 32-symbol reversal preamble (receivers' DCD on), varicode + "00" per character (CR is followed by LF),
    a 32-symbol steady-carrier postamble (DCD off), and fldigi's raised-cosine shaping between symbols.
  - RTTY: Baudot with LTRS/FIGS (FIGS re-sent after a space for unshift-on-space receivers), start, 5 bits and 1.5
    stop bits, continuous-phase FSK with mark high, and 10 ms ramps.
- PC round trip (`run_fldigi.sh`): the app's PSK31 and RTTY receivers copy the app's own transmissions exactly.
- `KbNative.encode` sends the message on the RX frequency (and shift) through Transmitter at 8 kHz. What was sent goes
  into the text as "[TX] ...". The SendBox (`TxUi.kt`) has a text field, Send, CQ, 73 and Callsign.
- CW uses the IC-705's own keyer over CI-V: 17 + text in 30-character pieces (only the keyer's characters), 17 FF to
  stop, and 14 0C to set the speed (6–48 WPM as 0–255 BCD). The CW page refuses unless the radio is in CW, and needs
  break-in on. It offers 15/20/25/30 WPM and Stop.
- Not yet tried on the air.

## 2026-10-09: 0.7.1, stage 7b (WSPR beacon)

- `wspr_run.c` `wspr_encode_audio`: WSJT-X's `get_wspr_channel_symbols` (wsprsim_utils.c, already built) produces 162
  symbols. They are sent as continuous-phase 4-FSK, 12000/8192 = 1.465 Hz apart and centred on the offset, 8192
  samples each (110.6 s), with 10 ms end ramps. JNI: `WsprNative.encode`.
- PC round trip (`tools/test/test_wsprtx.c`): "M7JVY IO91 23" and "G4ABC JO01 37" written as noisy slot WAVs and
  decoded by the app's wsprd exactly, with drift 0, on frequency and DT 0.0.
- `core/WsprBeacon.kt`: decides 2 s before each even minute with the chosen percentage (WSJT-X's Tx Pct), sends
  "CALL GRID4 DBM" at the even minute + 1 s through Transmitter, and receives the other slots. It refuses without a
  locator and reports why.
- WSPR page: beacon on/off, 10/20/33/50 %, power reported (200 mW–10 W), status, a red line at the offset, and a tap
  on the waterfall to move it (1410–1590 Hz).

## 2026-10-09: 0.7.0, stage 7a (FT8 and FT4 transmit)

- `ft8_slot.c` `ft8_encode_audio`: ft8_lib's message encoder and the GFSK synthesiser from its `demo/gen_ft8.c` (MIT),
  sharing the decoder's callsign hash table. JNI: `Ft8Native.encode`.
  - PC round trip (`tools/test/test_ft8tx.c`): CQ, grid, report, R-report, RR73, 73 and CQ DX, in FT8 and FT4, each
    encoded and decoded back exactly (DT 0.0, the frequency asked for).
  - `<PJ4/K1ABC>`-style hashed compound calls cannot be encoded by ft8_lib.
- `audio/Transmitter.kt` keys PTT over CI-V (1C 00), then plays the audio to the IC-705's USB sound card (an
  AudioTrack with its preferred device set to USB), then unkeys. It refuses without a callsign, CI-V or the USB
  output, so it never transmits through the phone speaker. It has a 130 s watchdog and Halt, keys 60 ms before the
  audio, and unkeys twice.
- `core/FtQso.kt`: WSJT-X-style contacts with the six standard messages.
  - Auto-sequencing from messages to you: grid → report, report → R+report, R-report → RR73 (logged), RR73/RRR → 73
    (logged), 73 → done.
  - Calling CQ: the first to answer becomes the DX.
  - Picking a decode sets the DX, its grid and our report (their SNR), and transmits in the opposite slot parity.
  - Transmissions start 0.5 s into the slot. The audio is prepared 0.4 s early, after the 14.7 s decode.
  - After 6 unanswered repeats transmitting turns off. 73 is sent once.
- `core/Logbook.kt`: an ADIF log (`files/log.adi`) with ADIF mode/submode names (FT4 = MFSK/FT4), band from the dial,
  and station call/grid. Settings > Logbook shares it through a FileProvider.
- `ui/TxUi.kt`: the licence notice (once; Settings can show it again), the TRANSMITTING banner with Halt, and the
  FT8/FT4 panel (TX on/off, Call CQ, Halt, 1st/2nd slot, Tx1–Tx6 chips with the next one lit, status). On the FT8
  page a tap on a decode answers that station and a tap on the waterfall sets the TX offset (red lines).
- Settings: transmit level (default 30%) and the licence notice again.
- Not yet tried on the air. It needs the phone connected to the IC-705 (the phone was disconnected from adb).

## 2026-10-09: first run on the phone (S23), decoders checked on ARM

- 0.6.0 installed on the S23. The startup screen, menu, and the FT8, JS8 and PSK31 pages work. The microphone feeds
  the waterfall, and the FT8 and JS8 slot decodes run at the slot end without trouble. Testing with the IC-705 needs
  the phone's USB socket, which adb was using.
- New debug-only check, `DevTest.kt` (`--ez dev_test true`, docs/DEV_COMMANDS.md). It runs the decoders on recordings
  copied into the app's files with run-as. Shared storage doesn't work, because the app can't read folders adb made
  there. Results on the phone match the PC:
  - FT8 ft8_lib 191111_110630: 12, websdr_test6: 20, WSJT-X sample: 8 (54–68 ms each).
  - FT4 WSJT-X sample: 7 (28 ms).
  - JS8 A_1_4: 5, A_2_9: 8 (170–270 ms; SNRs within 1–3 dB of the PC build, because of Eigen's NEON code).
  - WSPR WSJT-X sample: the same 9 spots (2 s).

## 2026-10-09: 0.6.0, stage 6 (JS8Call receive), so every mode now decodes

- JS8Call's C++ decoder (`JS8.cpp`, commit a7ff1be, GPL v3) is in `cpp/js8`. `tools/port_js8.py` makes the Android
  copy from the original. Boost is replaced by `js8_compat.h`: an augmented CRC, a constexpr round, and a vector-based
  SyncIndex with the same 40th-percentile normalisation and candidate selection. The Qt Worker/Decoder is replaced by
  `JS8::Engine`, running the same decoding pass on the caller's thread. Eigen is vendored, as JS8Call does. Details
  are in `js8/ANDROID_CHANGES.txt`.
- Message unpacking (`js8_unpack.cpp`) is a port of the receive half of JS8Call's DecodedText, Varicode and JSC from Qt
  to std C++, function by function. The JSC word table (`jsc_map.cpp`, 262144 entries) is copied unchanged and built
  at -O0. JSC words are Latin-1 and are converted to UTF-8.
- `js8_run.cpp` runs a 15 s Normal slot the way JS8Call schedules a full-cycle decode (kposA 0, kszA 15 s, submode A,
  0–5000 Hz). `js8_jni.cpp` returns tab-separated frames.
- `core/SlotAudio.kt`: the slot-timed 30 s ring, moved out of SlotDecoder. FT8, FT4 and JS8 now share it.
  `core/Js8Decoder.kt` decodes at 14.6 s and builds Band activity (frames within 10 Hz in the last 5 minutes are
  joined, ♢ marks a message's end, low-confidence frames are shown in [ ]), Calls (with the grid from heartbeats and
  km), and To me.
- `ui/Js8Screen.kt`: the radio bar, slot bar, a waterfall with the RX offset (and its 50 Hz) marked where a tap sets
  nfqso, and tabs for Band activity, Calls and To me.
- PC test (`tools/test/run_js8.sh`, needing `-D_USE_MATH_DEFINES -DEIGEN_DONT_VECTORIZE` for zig on Windows):
  - The CRC stand-in equals ft8_lib's FT8 CRC on 1000 payloads.
  - JS8Call's own test recordings give 26 decodes from 7 Normal files, against the 31 the old Fortran decoder's file
    names give.
  - Messages unpack as JS8Call shows them.
  - A_2_1's single weak (-21 dB) station decodes only with the window shifted 2 s. JS8Call improves on this by
    re-decoding sliding windows during the cycle; this is a possible later improvement.
  - Widening 100–3000 Hz to JS8Call's 0–5000 Hz made no difference, so JS8Call's range is kept.
- The debug APK is 54 MB (debug symbols, 3 ABIs, the JSC table). The release build will be smaller.

## 2026-10-09: 0.5.0, stage 5 (RTTY and PSK31 receive)

- fldigi 4.1.23 (GPL v3) goes into `cpp/fldigi`. The DSP building blocks are copied unchanged: fftfilt, filters,
  pskcoeff, pskvaricode and their headers, with an empty `config.h`. fldigi's full checkout fails on Windows, so only
  the needed folders were checked out.
- New receive-only editions keep fldigi's algorithms unchanged. `rtty_rx.cxx` covers the mark/space fftfilt filters,
  optimal ATC, the bit state machine, Baudot with unshift-on-space, and AFC. `psk31_rx.cxx` covers the PSKcore FIRs,
  bitclk timing, BPSK phase decision, quality/DCD, varicode, phase AFC and S/N-IMD. The UI, settings, other variants
  and transmit are removed. One change: fldigi took the RTTY metric from its waterfall, so here it comes from the
  filter envelopes. Details are in `fldigi/ANDROID_CHANGES.txt`.
- fldigi's headers are not on the global include path, because its `complex.h` would shadow the C library's. fldigi's
  files find their headers next to them.
- `fldigi_jni.cpp` keeps one receiver per mode, with a lock between the audio thread and the screen. It provides
  process, state (frequency, metric, s/n, DCD, IMD), text, and control (frequency, AFC, squelch, reverse, reset, shift).
- `ui/KeyboardScreen.kt` (RTTY and PSK31): the radio bar, a 0–3000 Hz waterfall (3.9 Hz bins) where a tap tunes and
  red lines mark the tones or carrier, RX frequency, s/n, DCD and a quality bar, AFC, a squelch slider (PSK 25, RTTY
  0), RTTY Reverse and shift (170/85/425/850), and the decoded text, kept per mode up to 20k characters, with
  Copy/Share/Clear.
- PC test (`tools/test/run_fldigi.sh`) on generated signals with noise and 3–4 Hz of mistuning. RTTY 45.45/170 copies
  the whole message at +10 and 0 dB (2500 Hz) and garbles below -5. PSK31 copies fully to -5 dB and with 1–2 errors at
  -8 and -10 dB. Both AFCs pull in. At first "73" came out as "UE": that was the test generator, which didn't re-send
  FIGS after a space as transmitters do for receivers using unshift-on-space. It is fixed in the test.

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

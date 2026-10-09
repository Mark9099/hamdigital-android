# HF Digital Modes: project log

Newest first.

## 2026-10-09: 0.12.0 (FreeDV RADE V1)

- **Asked for by the user:** "number 4 and 8" - 8 was FreeDV RADE.
- **Code chosen:**
  - rade_c (freedv/rade_c, BSD-2, c8a3dc1): the C port of RADE.
  - Opus at 940d4e5 (the commit rade_c builds) with its model file (sha256 checked): RADE's speech side, LPCNet
    features in and FARGAN speech out.
  - wfweb's `rade_text.c` (BSD-2 header): a self-contained, wire-compatible port of freedv-gui's callsign coder.
- **V1 only:** rade_c's README says V2 is pre-release and not for on-air use, and its weights are 62 MB more source.
  `rade_v2_stubs.c` stands in for the V2 functions `rade_api.c` refers to.
- **Opus subset, not Opus's autotools build:** 15 C files and the headers they include, found from the compiler's
  dependency output. rade_c's two patches to `dnn/nnet.h` / `nnet.c` are applied. No run-time CPU detection:
  `dnn/vec.h` picks NEON (ARM) or SSE (x86) at compile time.
  - Everything is in `cpp/rade/` (95 files, 71 MB of source, nearly all weights).
  - It builds as its own library, `libhamrade.so`, about 13.8 MB per ABI.
  - `rade/ANDROID_CHANGES.txt` lists every file's origin.
- **PC checks** (`tools/test/test_rade.c`, `run_rade.sh`, zig cc):
  - rade_c's `input_sample.wav`, round trip with callsign M7JVY: speech comes back at every SNR down to -2 dB. The
    callsign decodes with no noise and at 10 dB, not at 5 dB and below. That is the end-of-over frame's own limit:
    one frame, 56 bits LDPC coded.
  - rade_c's `FDV_offair.wav` (real off-air, resampled 48 to 8 kHz): 326 s decoded in 28 s on this PC (SSE2 only),
    2628 of 2717 frames in sync, and all four end-of-over callsigns: VK5KVA, VK3TPM, VK3TPM, VK5KVA. So the
    callsign coding matches freedv-gui's.
  - This PC has no AVX2 (an AVX2 build stopped with "Illegal instruction").
- **App:**
  - `rade_jni.c` and `RadeNative` make the same calls as codec2's bridge, plus `txEnd`, the end-of-over frame.
  - `FreeDv.kt` drives both through an `Engine` interface.
  - RADE speech runs at 16 kHz (mic and player); the modem stays at 8 kHz.
  - Receive: real audio x 2/16384 as IQ with imag 0 (rade_api.h). FARGAN starts from 5 frames and is reset at the
    end of each over.
  - Transmit: 12 LPCNet frames (120 ms) per modem frame, real part x 16384, the user's level on top.
  - RADE is the first mode chip and the page's default.
  - In RADE the squelch chip is hidden, "Callsigns heard" replaces "Text received", and the callsign from
    Settings is sent (no text field).
  - Waterfall marks 750-2250 Hz for RADE.
- **The end of an over was being cut off:** `IcomAudioUdp.stopTxStream` emptied the queue and PTT dropped at once,
  for every FreeDV mode.
  - The new `Transmitter.drainStream()` waits, up to 3 s, until the queue is empty (WiFi: `txQueued`) or the sound
    card has played everything written (USB: `playbackHeadPosition`).
  - The talk thread sends `txEnd` and drains before `stopStream`.
- DevTest: the FreeDV round trip now goes through `Engine` (RADE included, speech doubled to 16 kHz, callsign checked);
  `files/test/rade/*.wav` decodes a recording and reports the share of real time used.
- APK: the debug build is 100 MB (it was 58 MB); the .so files are stored uncompressed.
- **On the phone (S23):**
  - Moved to the release key: backup of `files/` and `shared_prefs` (`log.adi`, 5 contacts), uninstall, install, `tar`
    restore with run-as, and the microphone permission granted again with `pm grant`. The log is byte-identical to the
    backup. In Git Bash, adb device paths need `MSYS_NO_PATHCONV=1`, or /data/local/tmp becomes C:/Program Files/Git/...
  - DevTest, RADE: `FDV_offair.wav` (8 kHz) gives all four callsigns, 2628 of 2717 frames in sync, and 326 s decoded in
    21.7 s (7% of real time). The round trip at 5 dB brings speech back with callsign M7JVY. 700D, 700E, 1600 and SSTV
    are unchanged.
- **Found on the phone: Back from the menu never logged out of the radio.** Since Android 12, Back on an app's root
  activity only moves the task to the back without finishing it. `onDestroy(isFinishing)` never ran, so the WiFi link,
  the CI-V polling and the foreground service all carried on after "closing" the app. Seen with logcat: CI-V traffic
  after the app left the screen, and the activity still in its task.
  - Fix: Back on the menu now calls `finish()` (the BackHandler is always enabled), so `onDestroy` logs out, then stops
    the service.
  - Swiping the app from Recents already logged out (`RadioService.onTaskRemoved`). `cmd activity stack remove <task>`
    does the same from adb, and was used before the uninstall.
- **x86_64 dropped** (the user's decision): only the emulator, which this PC cannot run, and a few old Intel tablets used
  it. The ABIs are now arm64-v8a and armeabi-v7a. The release APK went from about 92 MB to 64.6 MB. It is one line in
  `app/build.gradle.kts` (`abiFilters`) to add back.
- **On air (2026-10-09, 19:32-19:46 UTC, IC-705 over WiFi, USB-D):** 5 minutes on 7.177 and 5 on 14.236 heard no
  RADE station. The waterfall showed band noise across the passband, so audio was arriving: the bands were just
  quiet then.
  - The radio's WiFi link came back by itself 70 s after an `adb install -r` killed the app without logging out
    (the IC-705's stale session).

## 2026-10-09: first release (0.11.1 APK on GitHub)

- **Asked for by the user:** a GitHub release with a signed APK, the way HF Propagation is released.
- **Release key:** created with the JBR keytool, PKCS12, RSA 4096, valid 100 years, alias `hamdigital`, DN "CN=HF Digital
  Modes Android, OU=Amateur Radio, C=GB", in `%USERPROFILE%\.android\hamdigital-release.jks`.
  - A random password, in `keystore.properties` (gitignored; the build already read it, copied from HF Propagation).
  - Certificate SHA-256: 7643ba4f6232ffaa8abee4ed70660a3b8a54c9c62b6181d6f97beb1bc0d936bf.
- **Debug builds are signed with the release key too** (`buildTypes.debug`), so test builds installed over USB and the
  released APK update each other and keep the logbook and settings. The phone's current debug-key install has to be
  replaced once: back up `files/` with run-as, uninstall, install, then restore.
- **GPL completeness:** the app is GPL v3 (WSJT-X, JS8Call and fldigi code), so the released APK needs its complete
  source available. The CW decoder came from HamPropCore, a private repository the public one couldn't build without.
  The user chose to copy it in: `cpp/cw/` (cw_decoder.cpp/.h, cw_goertzel.h, cw_morse_table.h, unchanged, from
  HamPropCore 2a176a5; `cw/ORIGIN.txt`). The CMake HAMPROP_CORE lookup is gone, and the repository builds on its own.
- **The file:** `assembleRelease` gives a 51 MB APK (arm64-v8a, armeabi-v7a, x86_64; target 35; not debuggable),
  copied to `dist/HF-Digital-Modes-0.11.1.apk`. `apksigner` verifies it with the key above.
- README: an "Install" section, and Building without HamPropCore.
- **Checked:** a fresh clone of the public repository (no keystore, no HamPropCore) builds debug and release (unsigned).
- **Released:** https://github.com/Mark9099/hamdigital-android/releases/tag/v0.11.1, with the APK attached (SHA-256
  ddf8a437d76df3d74b18b3318f89861eb16f38048485cb91ef489b67e04eed4a). The notes say what is tested on air and what isn't.
- Each later version: build `assembleRelease`, copy it to `dist/HF-Digital-Modes-<version>.apk`, then `gh release create
  v<version>` with that APK.

## 2026-10-09: 0.11.0 (SSTV and FreeDV)

- **Asked for by the user:** "could you add FreeDV and SSTV to this project? proceed".
- **Open-source code chosen (licences checked with `gh api`):**
  - codec2: drowe67, LGPL-2.1, 310777b.
  - Robot36: xdsopl, 0BSD, 2af2390.
  - SSTV Encoder 2: olgamiller, Apache-2.0, 8576681.
  
  All three can go in a GPL v3 app. Clones are in `_upstream/`.
- **SSTV receive (`core/SstvRx.kt`, `xdsopl/robot36/`).**
  - Robot36's decoder classes are copied unchanged (25 files, plus LICENSE). They are plain Java; three use
    android.graphics.Bitmap.
  - `SstvDecoder.java` (ours, in that package because Decoder is package-private) holds the scope and image
    `PixelBuffer`s as Robot36's MainActivity does.
  - Audio at 12 kHz. A finished picture (image.line == height) is saved as `files/sstv/SSTV_<UTC>_<mode>.png`, then
    image.line is set to -1 (Robot36's way of saving it once).
  - Our transmissions are not decoded.
- **SSTV transmit (`core/SstvTx.kt`, `om/sstvencoder/`).**
  - SSTV Encoder 2's Modes, ModeInterfaces and Output/IOutput are copied unchanged, with LICENSE and NOTICE.
  - `compose()` crops the picture to the mode's shape (middle kept), scales it, and writes the call and a line of
    text in white with a black edge.
  - `encode()` collects the samples through an IOutput into a ShortArray sized from init(samples) (an ArrayList of
    Shorts would be ~55 MB for PD 290), then sends them with Transmitter.send.
  - The Transmitter's USB watchdog is now max(130 s, the audio's length + 10 s), since PD 290 is 289 s.
- **Sideband:** `RigMode` (USB-D, LSB-D, CW) replaces the cw flag in `Ic705.setMode`/`tune`/`isIn`.
  `Mode.rigMode(khz)` gives LSB-D for SSTV below 10 MHz. RigBar's "(… needed)" note and AutoTune use it.
- **SSTV page (`ui/SstvScreen.kt`).**
  - A waterfall of 1000–2500 Hz.
  - Receive tab: the picture so far (or Robot36's scope, or the last picture), progress, a thumbnail strip, and a
    full-size dialog with Share (FileProvider), Save to Photos (MediaStore, Android 10+) and Delete.
  - Send tab: photo picker or camera (TakePicture into the cache; `cache-path` added to file_paths.xml), mode chips
    with size and seconds, top/bottom text, a preview, Send through the licence gate, and a progress bar.
  - Dials: 3.735 / 7.165 LSB, 14.230 / 21.340 / 28.680 USB.
- **FreeDV (codec2, `cpp/codec2/`).**
  - The library sources and headers are copied unchanged. The eight `codebook*.c` were generated on the PC (zig cc
    of generate_codebook.c, using upstream's command lines), because upstream generates them at build time, which
    can't run when cross-compiling.
  - `version.h` written for 1.2.0; GIT_HASH defined.
  - Built as the static library `codec2_hd`, with its KISS FFT functions renamed (`c2_kiss_fft*`) and its LDPC
    `encode` renamed (`c2_ldpc_encode`) by compile definitions, which fixed a duplicate `encode` with wsprd's fano.c.
    ft8_lib's KISS FFT stays as it is.
  - 2020 needs LPCNet, so it isn't built.
  - **PC test (`tools/test/test_freedv.c`, `run_freedv.sh`):** codec2's hts1a.raw through 700D, 700E and 1600 at 20,
    5 and 0 dB in 3 kHz. Sync came in about 2 frames; speech came back at -1.3 to +0.1 dB of the input level (700E at
    0 dB: -4.5).
- **FreeDV bridge (`freedv_jni.c`, `engine/FreeDvNative.kt`):** a session per handle with open/close/sizes/rx/tx/
  stats, the text channel (received characters collected; the send text repeated), and squelch.
- **FreeDV engine (`core/FreeDv.kt`).**
  - Receive: 8 kHz into frames of nin samples. Speech plays through an AudioTrack aimed at headphones, then
    Bluetooth, then the speaker, never the USB sound card. "Radio audio" passes the radio's audio through. Sync, SNR
    and text are StateFlows.
  - Talk: the phone's built-in mic (AudioRecord, 8 kHz), freedv_tx a frame at a time, the gain from the transmit
    level, into a stream.
- **Streamed transmit (new).**
  - `Transmitter.startStream/streamWrite/stopStream`: over USB, an AudioTrack at the stream's rate; over WiFi, linear
    resampling to 12 kHz into `IcomNet.streamPush`.
  - A 5-minute time-out and a link check every 200 ms; halt() ends a stream; send() is refused while one is on.
  - FT8CN's `IcomAudioUdp` gained `pushTxAudio/startTxStream/stopTxStream`: a 2 s queue and a thread sending one
    20 ms packet every 20 ms, paced to the clock, silence when empty (icom/ANDROID_CHANGES.txt). Its own
    `sendTxAudioData` sends a single recording and can't take live speech.
- **FreeDV page (`ui/FreeDvScreen.kt`):** a sync light and SNR, mode chips, Play (speech / radio audio / off),
  squelch, the received text, the text to send (your call), and a big talk button (hold, or tap on/off) through the
  licence gate. Dials: 3.643, 7.177, 14.236, 18.118, 21.313, 24.933, 28.330 MHz USB.
- **DevTest:**
  - `files/test/sstv/` present: every SSTV mode encoded and decoded (colour bars + gradient + "M7JVY"; Robot 36 also
    with noise). Logs the mode named, the size and the mean difference.
  - `files/test/freedv/*.raw`: each FreeDV mode round trip at 5 dB.
- **Checked on the S23 (DevTest, no radio):**
  - SSTV: all 15 modes came back as their own mode at the right size. The mean colour difference from the picture
    sent was 2–7 (PD, Scottie, Robot, Martin 1) and 13 (Martin 2), out of 255. Decoding ran at 100–150× real time
    (PD 290's 290 s in 1.9 s).
  - Robot 36 with noise: 26 dB → diff 8, 20 → 12, 14 → 19, 10 → 27; at 6 dB in 3 kHz the VIS code is missed (no
    picture, though the scope still shows the lines). Normal for analogue SSTV.
  - FreeDV at 5 dB: 700D sync 21/23 frames, speech -1.5 dB of the input; 700E 40/42, -1.3 dB; 1600 143/161, -0.5 dB.
    It runs at about 100× real time.
- **On the air (receive only, WiFi):**
  - Opening SSTV from 7.0386 USB-D tuned to 7.165.000 LSB-D, so the new LSB-D works. The 20 m chip gave 14.230.000
    USB-D.
  - Four minutes on 14.230 at 18:00 UTC: no SSTV signal. The page showed band noise as scan lines (the scope).
- **0.11.1:** the status said "last: Robot 36 Color" before any picture, which is the decoder's starting mode.
  `SstvRx.mode` is now set only once a picture has started (VIS heard).
- Transmitting SSTV and FreeDV on the air is still to do: it needs someone or something listening (another receiver
  or a WebSDR), as there is no automatic reporting network for either.

## 2026-10-09: 0.10.5 (fixes from the on-air tests)

- **The user confirmed CW does transmit:** BK-IN shows on the radio, and TX and the power meter pulsed during the CQ.
  Two more CQs (sent twice, 15:25:42 UTC, on 7.030 after checking it was clear) still drew no RBN spot. G4ZXN was
  spotted 500 Hz away at 18–30 dB in the same minutes, and the RTTY CQ at the same 2 W was spotted. Over WiFi the
  radio does not pass its sidetone back (the CW page decoded nothing of our own sending), so there is no check of the
  keying from here. Next: listen on a WebSDR during a CQ.
- **Fixes (user: "fix all"):**
  1. `Js8Tx` keeps the message label. After the last frame the status is "Sent: <label>"; on failure, the
     transmitter's reason ("Halted" is kept).
  2. `KeyboardScreen`: fldigi's receiver is not fed while we transmit, or for 0.6 s after
     (`Transmitter.sentDuring`). The waterfall carries on.
  3. `Ic705.askBreakIn()` (CI-V 16 47) puts `RigState.breakIn` (0 off, 1 semi, 2 full) in the state. The CW page asks
     every 5 s while open, and if it is 0, Send says to turn BK-IN on instead of sending.
  4. JS8 "4YZGXD/8UU HEARTBEAT SNR -21": our unpack matches JS8Call's DecodedText (a FrameCompoundDirected shows as
     "<compound><extra>"), so JS8Call would print it too, but the sender is not a callsign. `Js8Decoder.parse` now
     checks the sender against JS8Call's Varicode callsign pattern. One that fails is marked low-confidence (shown
     [in brackets]) and kept out of Calls.
- **Checked on the S23 (0.10.5):**
  - CW page: the radio answered 16 47 with 01 (semi break-in) three times; opening CW tuned to 7.030 CW.
  - JS8 heartbeat: "Sending frame [...]", then "Sent: heartbeat" at 15:44:00.
  - RTTY CQ: the text shows once, as the [TX] line, with no second copy from the monitor audio. PSK31 uses the same
    page code and was not transmitted again.
  - The JS8 false-decode rule only shows when one turns up.

## 2026-10-09: on-air tests of the other modes (0.10.4, 40 m, WiFi, 2 W)

- **FT4:** opening the page tuned to 7.047 and decoded CQs (II5GG Italy, F5OZC France, DC1OA Germany). Answering
  F5OZC by double-tap ran a complete contact: Tx1 ×5; his -12 at 15:08:07; R-09; his RR73 at 15:08:22; 73. It
  logged as MODE MFSK / SUBMODE FT4 / COUNTRY France. The transmit slots were not decoded (0.8.6).
- **JS8Call:** 7.078, receiving. One heartbeat at 15:10:30 was acknowledged by G0MDL (-10), M7EVV (-24), G0BMH
  (-12) and LA7HKA (-11) ("To me"). PSK Reporter has 10 reports, mode JS8: LA7HKA, PD5DLB, ON6URE, F6KGL, OE6ADD,
  EI4HQ (+4), ON8ST, M7NXS and others.
- **PSK31:** 7.040, with no PSK31 on 40 m to receive. Two CQs at 1081 Hz (7.041081): the page decoded our own
  transmission from the radio's monitor audio perfectly, so the radio was keyed and the audio is right. No outside
  report: no PSK Reporter or RBN PSK spot (no PSK skimmers heard it, or none were listening).
- **RTTY:** 7.040, with no RTTY to receive (squelch 0 prints noise, as fldigi does). One CQ at about 1011 Hz was
  **spotted by RBN** at 15:17Z on 7041.1: MM0ZBH 14 dB (twice) and MM9PSY 12 dB, 45 baud, CQ. PSK Reporter also has
  MM9PSY, mode RTTY. The monitor decode was perfect too.
- **CW:** 7.030 CW, receiving (it locked on a 600 Hz signal at 15 dB). One CQ at 20 WPM through the IC-705's keyer
  (CI-V 0x17) at 15:19:40: the radio replied FB to the speed and both message parts, but **no RBN spot** in two
  minutes, where the RTTY CQ at the same power was spotted at 12-14 dB. The keyer most likely played the sidetone
  without transmitting because the radio's break-in (BK-IN) is off. To check with the user.
- **RBN access for tests:** the telnet feeds `telnet.reversebeacon.net` 7000 (CW, RTTY) and 7001 (digital), logged
  in as M7JVY, recorded with bash `/dev/tcp` and grepped. The web `spots.php` API ignores `spotted_call`.
- **Found (to fix):**
  1. JS8 status stays "Sending frame [...]" after the last frame has gone.
  2. RTTY/PSK31 pages decode our own transmission (monitor audio) as well as printing the [TX] line, so the text
     appears twice.
  3. CW sending gives no warning if the radio's break-in is off.
  4. JS8 showed one false decode, "4YZGXD/8UU HEARTBEAT SNR -21".

## 2026-10-09: 0.10.4 (one mode at a time; no transmitting without the link)

- **Incident, found while starting the other-mode tests (14:43 UTC).** The FT4 page opened without retuning (AutoTune
  saw something sending) and showed TRANSMITTING. I pressed Halt at 14:44:12. From the log:
  - The WSPR beacon had been switched on on the phone at about 14:41.
  - At 14:41:58 the app was closed: `IcomNet.disconnect`, "WiFi connection closed". The beacon's timer lives in
    the process and carried on. Its 14:42 transmission had been arranged at 14:41:58 and keyed at 14:42:01 with the
    link closed. `IcomNet.ptt` and `sendAudio` do nothing without a link, but `Transmitter` still showed TRANSMITTING
    for 110 s.
  - At 14:42:42 the app was reopened and the link came back. The beacon's next transmission at 14:44:01 really went
    out, until the halt at 14:44:11 (FT8CN's audio sender logged "audio sending finished").
  - WSPRnet has no M7JVY reports after 13:26, so neither attempt was decoded.
  - The beacon then had to be switched off (it was already off when I went to do it).
- **Asked for by the user:** "when changing mode, must stop other modes from tx and rx".
- **Fixes:**
  - `core/TxControl.stopOthers(mode)` stops, for every mode but the one opened: an FT8/FT4 contact with TX on,
    the WSPR beacon, queued JS8 frames, the transmission on the air if another mode owns it (`Transmitter.owner`, set
    by each sender: FtQso, WsprBeacon, Js8Tx, RTTY/PSK31), and the IC-705's CW keyer.
  - It is called when a mode page opens (`ModePage`, in composition, before that page's AutoTune), and through
    `stopAll()` when the app closes (`MainActivity.onDestroy` finishing, `RadioService.onTaskRemoved`). Receiving
    already follows the page (AudioIn has one capture, the open page's).
  - `Transmitter` checks the link again when it keys: the CI-V link (USB), or the WiFi link and logged in
    (network). It stops a transmission if the link goes or changes (a reconnect) during it, and reports why in
    `lastError`.
- **Checked on the S23 (no transmission):** the beacon was switched on at 15:04:05 UTC, early in a 2-minute cycle
  (its first decision would be at 15:05:58). FT8 was opened, and back on WSPR at 15:04:30 it showed "Beacon off".

## 2026-10-09: 0.10.3 (WSPR map: keeping up with new stations)

- **Asked by the user:** "does the heard here auto update when new locations heard? didn't seem to".
- **Checked on the S23:** it does update. With nothing touched, Heard here went from 47 stations (92 reports) to 54
  (125) over two decodes (the list is the decoder's StateFlow, collected by the WSPR page behind the dialog). Three
  things could make it look as if it didn't:
  1. Stations only arrive at 1:54 of each 2-minute slot.
  2. A chosen time holds the map to that bin, and Play left the last bin chosen when it finished. My own test had
     left it there, so the map showed only the 14:12 slot while later slots were decoded.
  3. Each decode re-fitted the view (`fitTo` changed), undoing any zoom.
- **Fixes:**
  - Play ends on All.
  - If the newest bin is chosen when a new one appears, the choice moves to it (`lastBins`).
  - The choice resets if the bins change from slots to hours.
  - `WorldMap` re-fits for new stations only if the map hasn't been zoomed or dragged since the last fit (`moved`).
    Fit, and the other list (`MapDialog.view`), always re-fit.
  - The note says spots arrive "at 1:54 of each slot".
- **Checked on the S23 (0.10.3):**
  - With the map open and untouched, the first slot filled it with 14 stations (fitted).
  - After a drag, the next slot took it to 26 stations / 30 reports with the view unchanged; the new stations were
    GM4ISM, G4HSB, G8LZI, G6CKK, DK8EY, PD0PF and F4HHR. A second timeline bar (14:34) appeared.

## 2026-10-09: 0.10.2 (WSPR map timeline; one dot per shared locator square)

- **Asked for by the user:** a timeline on the WSPR map like wspr.rocks's. Its help describes a map "hours" slider
  that shows the spots of one hour of the day, with [auto] to step through and 2-minute slots on request.
  - Under the map: bars of reports per hour (Heard me: the last 24 h, ending at the next hour) or per 2-minute slot
    (Heard here, when it covers under 3 h; else per hour).
  - Tap or drag along the bars to show one bin's stations. Play steps 0.8 s per bin; All clears the choice.
  - The note line says "14:00-15:00 UTC: N stations (M reports)".
  - `MapDialog` and `WorldMap` take `fitTo` (every station of the list), so the view doesn't jump as the time changes.
  - "Heard me" (`WsprNet.heardMe`) now fetches every report (`toUnixTimestamp(time)`, rx_sign, rx_loc, snr,
    frequency, distance; limit 20000) instead of one row per station, so the timeline can count them.
- **Asked by the user:** "when zooming in on heard here, why do the locations seem to have two call signs?" WSPR's
  type-1 message carries a 4-character locator, and each station is placed at its square's centre. So every station
  in a square shared one point, and the label placer put one call on each side of the shared dot (others were
  hidden). Fix: `merge()` in `MapDialog` makes stations at the same point one dot, labelled "A, B" or "A +N", with
  its details listing each (the card scrolls). The WSPR list puts the strongest first, so a shared dot takes its
  colour. The Logbook map benefits too.
- **Checked on the S23:**
  - Heard me: 52 reports from 50 stations, all in the 13:00 bar (the two beacon transmissions). Tapping it showed
    "13:00-14:00 UTC: 50 stations"; tapping an empty hour showed 0. M9PSY and two others share a square, shown as
    "M9PSY-1 +2".
  - Heard here: after 3 slots, 32 stations from 41 reports, with three 2-minute bars (14:08, 14:10, 14:12) and
    shared dots "G4SXT, G0HFH", "DK2DB, DB1IAT" and "M0GUC +3".
  - Play stepped through the slots and stopped on the last.

## 2026-10-09: 0.10.1 (maps centred on you; 0.10.0 checked on the phone)

- **Asked for by the user:** use HF Propagation's centred map, which shows the land shapes better close in.
  - `map/MapProjection.kt` is now `CentredMap`, HF Propagation's azimuthal equidistant `Centred`, in unit
    coordinates (rim = 1, y down).
  - `WorldMap` projects the outlines once per centre (HF Propagation's `projectedPaths`: torn rings near the
    antipode are dropped, border and graticule lines are broken at jumps), then draws them translated and scaled. So
    pinch and drag don't re-project 80k points.
  - Paths to stations are straight lines from the centre (they are great circles). The rim and the area beyond it
    are drawn.
  - Fit frames the unit bounding box of the stations and you (70% of the width, leaving room for the calls). Zoom
    runs 1–400.
  - It is centred on your locator, or on the stations' middle if no locator is set.
  - The flat `FlatMap` was removed.
- **0.10.0 checked on the S23:**
  - cty.dat downloaded at start-up (106 KB).
  - Logbook rows show France and England, and the summary says "2 countries". The contacts map opened fitted to the
    four contacts, and tapping F6FHZ showed its details.
  - FT8 decodes showed France, Fed. Rep. of Germany, Spain, Italy, Netherlands and Latvia.
  - Auto-tune: opening FT8 moved 7.0386 to 7.074 USB-D, CW went to 7.030 CW, and WSPR went back to 7.0386 USB-D.
- **Checked on the S23 after the change:** the centred contacts map, and WSPR "Heard me" with 50 stations from
  wspr.live (EI4ACB, M9PSY, DC4HP-1, F5178SWL ...), fitted, with the SNR legend.
- The Logbook search box mentions country.
- **Asked for by the user:** no path lines on the WSPR map. `MapDialog` and `WorldMap` take `paths` (default on, for
  the Logbook); the WSPR map passes `false`. Checked on the S23.

## 2026-10-09: 0.10.0 (countries, maps, tuning on opening a mode)

- **Asked for by the user:** a country for every station on every page (the Logbook included); a Logbook map of the
  logged contacts that zooms to fit them; a WSPR map of the stations; and opening a mode tuning the radio to that mode.
- **Countries: `core/Cty.kt`**, HF Propagation's cty.dat reader (AD1C, country-files.com).
  - Exact calls first, then the longest prefix; portable forms are handled.
  - Downloaded at start-up if missing or more than 30 days old (on a thread), as HF Propagation does. Until the first
    download there are no countries. `Cty.loaded` tells pages when the list arrives.
- **Where countries show:**
  - a country column on FT8/FT4 decodes (the sender);
  - a second line on WSPR spots (the rows had no room for another column);
  - next to the call in JS8 Calls and in Logbook rows;
  - in Logbook search and the summary line (a country count);
  - in the form, as a Country field filled in from the call until it is typed over.
  
  `Qso.country` is written as ADIF COUNTRY, and `Logbook.add` fills it in from the call when it's empty. Older entries
  show the looked-up country (`countryName`).
- **Map: `ui/WorldMap.kt`.**
  - HF Propagation's `world.bin` (Natural Earth 1:50m, public domain; `tools/gen_world_asset.py` copied too), its
    `WorldData` reader and flat projection (`map/`), and its colours, path scaling and east–west wrap.
  - Dots with great-circle lines from your locator (a white diamond), labels that don't overlap, pinch zoom (1–120×) and pan.
  - Fits on open, and with Fit: longitudes are taken relative to your own, so a spread across 180° stays together,
    and the view is filled to about 80%.
  - Tap a dot for its details. A hollow dot is placed at the country's middle (cty.dat) when there's no locator.
  - `MapDialog` is a full-screen dialog, so a mode page keeps decoding behind it.
- **Logbook map** (a Map button beside the counts) maps the contacts the list shows, one dot per station at its
  latest contact, coloured by band, with a band legend.
- **WSPR map** (Map in the top bar) has two views:
  - Heard here: this page's spots, the best report per call.
  - Heard me: who reported the user's call in the last 24 h, from wspr.live (`WsprNet.heardMe`: a ClickHouse query
    over https, with only callsign characters allowed into it).
  
  Dots are coloured by SNR (HF Propagation's steps).
- **Opening a mode tunes the radio** (`AutoTune` in `RigBar`, also called by the pages that hide RigBar when sideways).
  - Once per opening, as soon as the radio's frequency is known, it tunes to the mode's dial on the band the radio is
    on (else the nearest band the mode has), with USB-D or CW.
  - Nothing happens if the radio is already within 50 Hz in the right mode.
  - Never while something is being sent or due to be: `Transmitter.on`, an FT8/FT4 contact with TX on, the WSPR
    beacon on, or JS8 frames queued.
- **About (Settings):** credits for Natural Earth, cty.dat (AD1C), FT8CN, WSPRnet and wspr.live.
- Built; not yet checked on the phone (it was off USB).

## 2026-10-09: 0.9.1 (WSPR on the air: dial fix, WSPRnet upload)

- **Root cause: WSPR received nothing from the band chips.** `Mode.dialsKHz` held whole kHz, so WSPR's dials were
  rounded down: 7038 instead of 7038.6, 14095 instead of 14095.6, 10138 instead of 10138.7, and so on. The 200 Hz
  WSPR window then sat 200–700 Hz above 1400–1600 Hz of audio, outside the range wsprd searches and the waterfall shows.
  The decoder tests had passed because they used recordings, not the band chips. Fix: the table holds fractional kHz,
  with the WSPR dials set to WSJT-X's (1836.6, 3568.6, 5287.2, 7038.6, 10138.7, 14095.6, 18104.6, 21094.6, 24924.6,
  28124.6, 50293.0). `Ic705.tune` rounds to the Hz, and the band bar shows four decimals where they're needed.
- **On the air (40 m, WiFi):** 17 spots in the first full slot (13:14 UTC, -6 to -33 dB, the UK, the Netherlands,
  Germany and Scotland, up to 826 km) and 12 in the 13:20 slot.
- **WSPRnet upload (`core/WsprNet.kt`, ported from WSJT-X's `Network/wsprnet.cpp`, GPL v3).**
  - Each slot's `wspr_spots.txt` lines (the file WSJT-X reads) are parsed with WSJT-X's pattern and message types
    (CALL GRID4 DBM, CALL/x DBM, <CALL> GRID6 DBM; unresolved `<...>` skipped).
  - Only spots within 10 kHz of the dial are sent. Each is POSTed to wsprnet.org/post/ (https, which takes the same
    form: checked with a GET) as function=wspr, with rcall/rgrid/rqrg/version/mode=2.
  - A slot with no spots sends function=wsprstat (tpct, tqrg and dbm from the beacon).
  - The reply is checked for "spot(s) added", as WSJT-X does.
  - **Off by default** (Settings `wsprUpload`), because it publishes under the user's call. Switched on the WSPR
    page, with the last result shown beside the switch.
- **The beacon's reported power** is now kept in Settings (`wsprDbm`); it used to go back to 5 W at each start.
- The `<...>` of a call wsprd couldn't resolve shows as `<...>` (was `...`).
- **Checked on the air (user's go-ahead: 2 W, upload on, beacon test).**
  - Upload: "WSPRnet 1323: 10 spots uploaded", and wspr.live shows 10 spots from rx M7JVY for 13:22.
  - Beacon: "M7JVY IO91 33" in the 13:24 slot was heard by 18 stations on 7.040100 ± 10 Hz (wspr.live), from
    G4KCM (15 km, -2 dB) to DC4HP-1 (760 km, -25 dB), including DF8OE, F5178SWL, PE1RQJ, M9PSY (-10 dB), G0DHD,
    PA3FNY and ON5HB.
  - The beacon slot was not decoded (0.8.6). The beacon was switched off again after the test, which halted a
    second transmission (13:26) part way through.

## 2026-10-09: 0.9.0 (Logbook)

- **Asked for by the user:** "a fully functional logbook for QSO contacts".
- **`core/Logbook.kt` (rewritten).** The log stays an ADIF file (`log.adi`), but the whole log is now held in memory
  (a StateFlow, newest first) and rewritten after each change. Each save writes a temporary file and renames it over
  the old one. Features:
  - add, update, delete and delete-all;
  - import, skipping a contact with the same call, band and mode within 2 minutes;
  - `withCall`, for "worked before".
  
  `Qso` gained a band (kept only when there is no frequency), name, QTH, power, comment and `extra`.
- **ADIF parser.**
  - Header handling, `<NAME:LEN[:TYPE]>` fields, case-insensitive names, `<EOR>`.
  - TIME_ON as HHMM or HHMMSS; a contact past midnight with no end date.
  - Submodes: FT4/JS8 under MFSK, PSK31 under PSK, and SSB with its USB/LSB sideband kept.
  - OPERATOR used when STATION_CALLSIGN is missing.
  - Fields the app has no box for are kept in `extra` and written back unchanged.
- **Logbook page (`ui/LogbookScreen.kt`), opened from the menu.**
  - A search box, band and mode chips, and a summary line (contacts, stations, squares, bands).
  - Rows with time, call, band, mode, reports, locator, km, name, QTH and comment.
  - Add; tap a row to edit.
  - The ⋮ menu: share (FileProvider), save to a file (SAF CreateDocument), import (SAF OpenDocument), delete all (asks first).
- **`QsoEditor`.**
  - A full-screen form, opened as a dialog so a mode page keeps decoding behind it.
  - Validation, with what's missing shown under the title.
  - A "worked before" line; delete asks first.
  - New contacts are filled in with the time, the radio's frequency, the mode and the usual report.
- **Log buttons on the mode pages:**
  - a Log chip in the send box on CW, RTTY and PSK31 (in the top bar when sideways);
  - Log in the top bar on JS8 and FT8/FT4, where FT8 fills in the contact in progress (`FtQso.draft()`, which
    auto-logging also uses).
- **FT8/FT4 list:** "B4" marks a sender already in the log on this band and mode.
- **Bug found while testing (pre-existing).** A page opened while the WiFi link was reconnecting kept "No audio" and
  the error for good: `AudioIn.start` returned the link status as an error, although the capture was running.
  `AudioIn.start` now returns no error for WiFi, and `RxStatus` shows the link's state live (amber) while it is down.
- **Tested on the S23.**
  - DevTest ADIF round trip (`files/test/adif`): a WSJT-X-style record, a CW record with no frequency and LoTW
    fields, an SSB contest record past midnight with N1MM fields, an MFSK/FT4 record with OPERATOR, and a record with
    no call (skipped). All 4 read correctly, and write → read is identical.
  - On the air: B4 showed on a live decode of F6FHZ (in the log on 40 m FT8).
  - On the page: edit (saved to the file), import through the file picker (4 added), import again (1 added, 3
    skipped), search, delete (confirmed, gone from the file), and the CW page's Log form filled in (CW, 599/599).
  - The user's real log (M7CYY plus M0IEP, F5PEG and F6FHZ, worked by the user at 12:01–12:22 UTC) was backed up
    first (`files/log_backup_before_0.9.adi` on the phone). The test entries are to be removed by restoring it.

## 2026-10-09: 0.8.6 (first complete FT8 contact; own transmissions no longer decoded)

- **On the air, 40 m FT8 over WiFi:**
  - The first complete contact: M7CYY (IO70), sent +01, received -03. The sequence ran by itself: Tx1 four times,
    their report, R+01, their RR73, then 73. It was logged to `log.adi` with the correct ADIF fields.
  - Answering PD4RJ earlier got no reply after 6 tries, and the transmit watchdog turned TX off as designed.
  - PSK Reporter showed 30+ stations hearing us in those minutes, from -25 to +8 dB, including TF4M in Iceland.
- **Found on the air:** our own transmission showed in the decode list at +40 dB, because the IC-705 passes its
  transmit audio back while keyed. Fix: `Transmitter` records when each transmission is keyed and unkeyed
  (`sentDuring`). FT8/FT4 (`SlotDecoder`), JS8 and WSPR skip any slot a transmission overlapped, as WSJT-X does (the
  radio hears nobody else while it transmits). The slot line then says "Your transmit slot (not decoded)".
- **Testing note:** a tap planned from a 13 s-old screenshot hit a row that new decodes had pushed into place
  (DD1UN). It was halted before its slot, so nothing was sent. For adb-driven tests, find the row in a fresh
  `uiautomator dump` and tap it 2–7 s into a slot.
- **Checked on the air:** 0.8.6 logged in to the radio 0.25 s after opening. Calling DJ2MS (who worked DB5HS instead):
  each of our 6 transmit slots showed "Your transmit slot (not decoded)", and no +40 dB line appeared. The slots
  between still decoded normally (4–10 messages each).

## 2026-10-09: 0.8.5 (WiFi: reopening the app reconnects at once)

- Reported by the user: after reopening the app it often didn't reconnect, or got WiFi but no radio control. The
  radio was keeping the old session, because the app's logout never reached it. There were three causes:
  1. **The service stopped too early.** Closing the app stopped the foreground service at the same moment as the
     logout, so Samsung blocked the app's network and the logout packets failed with EPERM. Fix: `IcomNet.disconnect(then)`
     stops the service only after the logout has been sent. `RadioService.onTaskRemoved` does the same when the app is
     swiped away.
  2. **FT8CN's UDP client closed its socket before its queued packets went out,** and reused one shared send task, so
     packets close together could be lost. Fix: one task per packet on a single-thread executor, and the queue drains
     before the socket closes.
  3. **FT8CN's close order was wrong.** The CI-V close was sent after its socket had shut, and the control stream
     closed first. Fix: CI-V close, then audio close, then token delete, then control disconnect, as wfview does.
  Details are in `icom/ANDROID_CHANGES.txt`.
- On the radio: three rounds of close then reopen 3 s later each reconnected with CI-V flowing in 2–3 s, with no
  EPERM and no send-queue timeouts.

## 2026-10-09: 0.8.4 (WiFi login root cause)

- After a clean logout the radio accepted the next login within 65 ms. Half a second later the app's first CI-V
  poll failed with "sendto failed: EINVAL": it went out before the radio's status packet had given the CI-V port,
  so it was sent to port 0. FT8CN's code treats any send error as fatal and closed everything. That left a
  half-open session on the radio, which caused the run of "no login" retries.
- Fix: IcomNet sends nothing on the CI-V or audio stream until that stream's radio port is known (`civReady` /
  `audioReady`). The next 1 s poll repeats anything dropped.
- The watchdog no longer gives up on a fresh login after 8 s without CI-V. It allows 60 s, because the radio may
  still be letting go of an old session's CI-V stream. Once CI-V has flowed, 8 s of silence still triggers a
  reconnect.
- On the radio: a clean exit and reinstall connected on the first try, with CI-V flowing and no errors.

## 2026-10-09: 0.8.3, first transmissions (FT8 over WiFi)

- Call CQ on 40 m went out at 07:47:15 UTC: PTT over CI-V ("1C 00 01" / "00" echoed), audio over the network.
- PSK Reporter shows M7JVY heard by 10 stations within minutes, all at 7.0755 MHz (7.074 + 1500 Hz):
  - EI4ACB +4, ON8ST -11, CT1ILT -12, F4FPR -13, M9PSY -14, EI7IN -14, GM0MST -15, F4LTX -16, F4DAI -19, EI4HQ -10.
  - Transmit audio, timing and PTT over WiFi work.
- Two unintended calls to GJ0KYZ followed (07:47:45, and 07:48:15 halted after 4 s).
  - Cause: a test tap aimed at the licence notice arrived 10 s after the user had already accepted it, and landed on
    a decode line. Samsung's input log (ViewPostIme) showed the touches.
  - The app did what a tap means. But one stray touch on the list could start a call, so calling from the FT8/FT4
    list is now a double-tap, like WSJT-X's double-click. A single tap does nothing.
  - Halt stopped the transmission at once ("1C 00 00").
- The licence notice and Guide now say DATA MOD = USB with the lead and WLAN over WiFi. They used to say USB only.
- No message to M7JVY was decoded. The automatic sequencing was not involved.

## 2026-10-09: 0.8.2, first test with the IC-705 (WiFi)

- **It works over WiFi:**
  - Login to the IC-705 (192.168.0.39, found by its Icom MAC 00-90-C7).
  - CI-V both ways: frequency and mode readout, and the 40 m chip tuned the radio to 7.074.000 USB-D.
  - Receive audio feeds the waterfall, and FT8 decodes off air on 40 m: 15 in one slot, 49 in the first minute,
    DT 0.1–0.4 s, sensible reports and distances.
- **Fault 1:** sends failed with EPERM and the link died whenever the phone was not showing the app. Samsung's
  background-app control (FreecessController, logged at an app switch) cuts a background app off the network.
  - Fix: `RadioService`, a foreground service with types connectedDevice|microphone and an ongoing notification. It
    starts on resume and stops when the app closes, keeping the link and receive audio running in the background.
  - Manifest: FOREGROUND_SERVICE(+_CONNECTED_DEVICE, _MICROPHONE), CHANGE_NETWORK_STATE, POST_NOTIFICATIONS.
- **Fault 2:** after an unclean end (process killed, link lost) the radio holds the old session for about 1–2
  minutes and ignores new logins. FT8CN asks "are you ready" only once, so the app was stuck for good.
  - Fix: IcomNet now keeps a target and runs a watchdog every 2 s. With no login after 10 s or no CI-V for 8 s, it
    logs out and retries at 5, 10, 20 and 30 s. On the air it got in at try 5, about 2 minutes after the kill.
  - It also logs out when the app closes (onDestroy while finishing).
- **Fault 3:** Android may route the app's packets over mobile data. Fix: the app's traffic is pinned to the phone's
  WiFi (`bindProcessToNetwork`), and a WiFi NetworkCallback waits on loss and reconnects on return.
- Display fixes:
  - Your callsign and locator now reach the FT8, WSPR and JS8 transmit panels during composition. Before, the page
    showed "Set your callsign" and a bare "Tx6 CQ" until something else changed.
  - The menu says "IC-705 connected over WiFi: audio + control".
- Menu path corrected (user): MENU > SET > WLAN Set > Remote Settings > Network User1. The Guide, Settings and code
  comments had a wrong "SET > Network" path.
- Still to test: transmit over WiFi, the other modes on air, and the USB lead.

## 2026-10-09: 0.8.1 (checked on the S23)

- 0.8.0 on the S23: the FT8 transmit panel, the WSPR beacon row, the JS8 send panel and the Settings WiFi section all
  display and work without crashes (no radio attached).
- Fixes from that look:
  - The WiFi password is hidden. CompactField has a `password` option.
  - Until a callsign and locator are set, the FT8/FT4 panel and the WSPR beacon line ask for them. The beacon line
    used to show `"  37"`.
  - The WSPR status reads "Decodes at 1:54", where it used to be cut off.
- Repository published: https://github.com/Mark9099/hamdigital-android (public, GPL v3). The commits were rewritten to
  the GitHub no-reply address before the first push.

## 2026-10-09: 0.8.0, stage 8 (the IC-705 over WiFi)

- FT8CN's Icom network-protocol classes (MIT) are copied into `uk.hamdigital.icom` by `tools/port_icom.py`, with the
  protocol code unchanged. Changes: English stream names, no extra volume scaling, the PTT CI-V frame built in place,
  and a status callback instead of toasts. FT8CN's phone-speaker playback isn't used. Details are in
  `icom/ANDROID_CHANGES.txt`.
- `rig/IcomNet.kt`: connect/close on a background thread. It passes CI-V to and from Ic705, receive audio (12 kHz LE
  16-bit) to AudioIn, PTT (which also opens the transmit stream), and transmit audio (12 kHz floats).
- Ic705: commands go over WiFi when `net` is on. `netFrame` splits incoming CI-V into frames for the same handler, a
  1 s poll timer replaces the USB reader's poll, and USB is closed while WiFi is in use.
- AudioIn: Receive audio "IC-705 over WiFi". The network audio is linearly resampled from 12 kHz to the page's rate
  (8 / 12 / 16 kHz) and fed to the same decoders and waterfalls.
- Transmitter: over WiFi it keys PTT through IcomNet, resamples the audio to 12 kHz floats, lets the protocol code
  stream it in 20 ms packets, waits for its length, and unkeys. `blocked()` requires the login.
- Settings: "Connection: WiFi (no lead)" with IP, port (50001), Network User1 name and password, Connect over WiFi
  (which also sets Receive audio to WiFi), Use the USB lead, and status. The app connects at start when WiFi is chosen.
- All 8 stages in docs/PLAN.md are now built. None of it has been tried with the radio yet: receive and transmit
  over USB and WiFi, CI-V and PTT all need checking on the IC-705.

## 2026-10-09: 0.7.3, stage 7d (JS8 sending), so stage 7 is complete

- `cpp/js8/js8_pack.cpp`: JS8Call's packers ported from Qt. They build heartbeat, CQ, directed-command and
  directed/@ALLCALL text frames as buildMessageFrames does (first/last flags), with Huffman data frames, and the
  audio as JS8Call's Modulator makes it. Free text to everyone is a directed message to @ALLCALL, because ':' cannot
  go in a Huffman frame. Details are in `js8/ANDROID_CHANGES.txt`.
- PC round trip (`tools/test/test_js8tx.cpp`) through the app's JS8 receiver. Each came back as JS8Call shows it:
  - "M7JVY: @HB HEARTBEAT IO91"
  - "M7JVY: @ALLCALL CQ CQ CQ IO91"
  - "M7JVY: G4ABC SNR?"
  - "M7JVY: G4ABC SNR -12"
  - a 3-frame "M7JVY: G4ABC" / "HELLO FROM THE" / "PHONE APP", with first/middle/last bits 1/0/2
  - "M7JVY: @ALLCALL TESTING 123"
- `core/Js8Tx.kt` queues a message's frames and sends one a slot (slot + 0.5 s) on the receive offset through
  Transmitter. It has Halt and status.
- JS8 page: a To field (tapping a call in Calls fills it and moves the RX/TX offset there), Message + Send (to the
  station, or @ALLCALL), HB, CQ, SNR?, GRID?, ACK, 73, Halt and status.
- Stage 7 is done: every mode transmits. Nothing has been tried on the air yet.

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

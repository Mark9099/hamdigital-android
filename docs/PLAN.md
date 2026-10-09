# HF Digital Modes: plan

An all-in-one Android app for amateur-radio HF digital modes, for the Icom IC-705. It has HF Propagation's look:
colours, Orbitron title font and the startup-screen layout. A startup screen leads to a menu, and each mode has its own
page. The decoders are reused open-source code rather than written from scratch.

## Decisions (2026-10-08, from the user)

| Question | Answer |
|---|---|
| Modes | FT8, FT4, WSPR, JS8Call, RTTY, PSK31, CW (CW uses the Morse decoder from HF Propagation, HamPropCore `cw_decoder.cpp`) |
| Radio link | USB lead first (audio + CI-V over one cable), WiFi later |
| Transmit | Receive first on every mode, then transmit |
| Licence | GPL v3, public GitHub repository (needed to reuse WSJT-X, JS8Call and fldigi code) |
| Stack | Kotlin + Jetpack Compose + NDK (C/C++ decoders), as HF Propagation |

## Open-source code used

| Mode | Code | Licence | Notes |
|---|---|---|---|
| FT8, FT4 | [ft8_lib](https://github.com/kgoba/ft8_lib) (Kārlis Goba) | MIT | C, no dependencies. FT8CN (MIT, an Android FT8 app with IC-705 support) uses it too |
| WSPR | `lib/wsprd` from WSJT-X | GPL v3 | C; needs FFTW (single precision), built from source with the NDK |
| JS8Call | `JS8.cpp` + `varicode.cpp` from [JS8Call](https://github.com/js8call/js8call) | GPL v3 | C++20 decoder (no longer Fortran). Needs Eigen (vendored), Boost headers (CRC, multi_index) and FFTW. Qt is removed from the parts used |
| RTTY, PSK31 | `rtty.cxx`, `psk.cxx`, filters from [fldigi](https://github.com/w1hkj/fldigi) | GPL v3 | The modem DSP is separated from fldigi's FLTK UI and settings with small stand-ins |
| CW | HamPropCore `cw_decoder.cpp` (from Tab5CWDecoder), copied into `cpp/cw/` | own (GPL v3 here) | Shared with HF Propagation and the Tab5 (`cw/ORIGIN.txt`) |
| SSTV | [Robot36](https://github.com/xdsopl/robot36) (receive), [SSTV Encoder 2](https://github.com/olgamiller/SSTVEncoder2) (send) | 0BSD, Apache 2.0 | Java, copied unchanged |
| FreeDV 700D / 700E / 1600 | [codec2](https://github.com/drowe67/codec2) | LGPL 2.1 | `cpp/codec2`, codebooks generated on a PC |
| FreeDV RADE V1 | [rade_c](https://github.com/freedv/rade_c) + [Opus](https://github.com/xiph/opus) (LPCNet, FARGAN), `rade_text` from [wfweb](https://github.com/adecarolis/wfweb) | BSD 2-Clause, BSD 3-Clause | `cpp/rade` (its own library, libhamrade.so; weights compiled in) |
| IC-705 USB serial (CI-V) | [usb-serial-for-android](https://github.com/mik3y/usb-serial-for-android) | MIT | CDC-ACM driver for the IC-705's USB serial ports |
| IC-705 WiFi | FT8CN's `icom` package (Icom network protocol) | MIT | Stage 8 |

The upstream copies used for reference are in `Documents\AndroidStudioProjects\_upstream`, as shallow git clones that
are not part of this repository. Each copied library goes into `app/src/main/cpp/<name>/` (native code) or its own Java
package (Robot36, SSTV Encoder 2) with its LICENSE and an `ANDROID_CHANGES.txt` listing what was changed.

## Stages

1. **Skeleton (0.1.0).** Done. Startup screen, mode menu, page per mode, Settings, Guide. Receive audio from the
   IC-705's USB sound card (or the microphone) and a waterfall on every page. CW decoder working.
2. **IC-705 control (CI-V over USB) (0.2.0).** Done. Read and set frequency and mode, so a band chip on a page tunes the radio to
   that mode's frequency in USB-D. Show the radio's frequency on each page.
3. **FT8 and FT4 receive (ft8_lib) (0.3.0).** Done. Slot timing from the UTC clock, decode each slot, and a list of the stations
   heard (call, report, DT, offset, message, distance from the locator). CQ calls are highlighted.
4. **WSPR receive (wsprd; FFTW calls on KISS FFT) (0.4.0).** Done. Two-minute slots and a list of the spots heard.
5. **RTTY and PSK31 receive (fldigi) (0.5.0).** Done. Tap the waterfall to tune, then show the decoded text.
6. **JS8Call receive (JS8 decoder + varicode) (0.6.0).** Done. JS8 Normal mode, the stations heard, and their messages.
7. **Transmit (0.7.0-0.7.3).** Done. PTT over CI-V and transmit audio to the IC-705's USB sound card. FT8 and FT4 QSO sequencing, the WSPR
   beacon, RTTY and PSK31 typing, JS8 messages, and CW keyed over CI-V.
8. **WiFi link (0.8.0).** Done. The IC-705's network protocol (from FT8CN's `icom` package) for audio and CI-V without a lead.
9. **Logbook (0.9.0).** Done. Asked for by the user after the first on-air contacts. Every contact is kept in ADIF. The
   Logbook page lists, searches, filters by band and mode, edits and deletes contacts, and shares, saves and imports
   ADIF files (skipping duplicates, keeping unknown fields). Each mode page has a Log button, and FT8/FT4 marks stations
   worked before (B4).
10. **SSTV (0.11.0).** Built; to be checked on the air. Asked for by the user. Receive with Robot36's decoder (Ahmet Inan,
    0BSD; `xdsopl/robot36`), send with SSTV Encoder 2's modes (Olga Miller, Apache 2.0; `om/sstvencoder`), both Java,
    copied unchanged. LSB-D below 10 MHz.
11. **FreeDV (0.11.0).** Built; to be checked on the air. Asked for by the user. codec2 (David Rowe and others, LGPL 2.1;
    `cpp/codec2`): 700D, 700E and 1600, with receive to the phone's speaker and transmit from its microphone through a
    new streamed transmit path (FT8CN's WiFi sender gained a 20 ms packet queue).
12. **FreeDV RADE V1 (0.12.0).** Built; checked on a PC (round trip, and a real off-air recording with its callsigns);
    to be checked on the phone and on the air. Asked for by the user. rade_c (BSD-2) with the parts of Opus it uses
    (BSD-3) and wfweb's wire-compatible `rade_text` for the callsign; V2 is left out (pre-release). On the FreeDV page
    as a fourth mode; the end of an over is drained to the radio before PTT drops (`rade/ANDROID_CHANGES.txt`).

## Testing

The emulator cannot run on this PC (the CPU is too old). Testing is on the user's Samsung S23 or Galaxy Tab S8 over adb,
with the IC-705 plugged into the phone.

# HF Digital Modes (Android)

HF Digital Modes is an all-in-one Android app for amateur-radio HF digital modes: FT8, FT4, WSPR, JS8Call, RTTY, PSK31,
CW, SSTV, FreeDV digital voice (RADE, 700D, 700E, 1600), Olivia, weather fax and APRS / packet. It is made for the Icom IC-705, connected to the phone or tablet by a single USB lead. Each mode has its own
page, reached from a menu after the startup screen. The look matches the HF Propagation app. A logbook keeps every
contact in ADIF, and can import, edit, search, share and save it.

The decoders are reused open-source code: ft8_lib, WSJT-X's wsprd, the JS8Call decoder, fldigi's modems, the CW
decoder shared with HF Propagation, Robot36 and SSTV Encoder 2 for SSTV, and codec2 and rade_c (with Opus's FARGAN vocoder) for FreeDV. See [docs/PLAN.md](docs/PLAN.md) for what comes from where and the stage plan, and
[docs/PROJECT_LOG.md](docs/PROJECT_LOG.md) for progress.

## Licence

GNU General Public License v3 ([LICENSE](LICENSE)), because it includes GPL code from WSJT-X, JS8Call and fldigi. Each
library under `app/src/main/cpp/` keeps its own licence file.

## Install

Download the APK from the [Releases](https://github.com/Mark9099/hamdigital-android/releases) page on the phone or
tablet and open it (Android asks once to allow installing apps from the browser or file manager). Android 8.0 or newer.
Each release installs over the previous one and keeps the logbook and settings.

## Building

You need Android Studio, with SDK Platform 35, the NDK (Side by side) and CMake from the SDK Manager. Everything the
app is built from is in this repository (the CW decoder is a copy from HF Propagation's shared code: `app/src/main/cpp/cw/ORIGIN.txt`).

To build from a terminal, set `JAVA_HOME` to Studio's `jbr` folder, then run `gradlew assembleDebug`. A release APK
(`gradlew assembleRelease`) is signed when `keystore.properties` names the release key (not in this repository).

## Using it with the IC-705

1. Connect the radio's USB-C socket to the phone (a USB-C to USB-C lead or an OTG adapter).
2. Open a mode, allow audio recording, and tune the radio to that mode's frequency in USB-D.
3. The page's waterfall shows the radio's audio. The level is set on the radio under MENU > SET > Connectors > USB AF/SQL.

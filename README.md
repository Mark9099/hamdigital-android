# HF Digital Modes (Android)

HF Digital Modes is an all-in-one Android app for amateur-radio HF digital modes: FT8, FT4, WSPR, JS8Call, RTTY, PSK31
and CW. It is made for the Icom IC-705, connected to the phone or tablet by a single USB lead. Each mode has its own
page, reached from a menu after the startup screen. The look matches the HF Propagation app.

The decoders are reused open-source code: ft8_lib, WSJT-X's wsprd, the JS8Call decoder, fldigi's modems, and the CW
decoder shared with HF Propagation. See [docs/PLAN.md](docs/PLAN.md) for what comes from where and the stage plan, and
[docs/PROJECT_LOG.md](docs/PROJECT_LOG.md) for progress.

## Licence

GNU General Public License v3 ([LICENSE](LICENSE)), because it includes GPL code from WSJT-X, JS8Call and fldigi. Each
library under `app/src/main/cpp/` keeps its own licence file.

## Building

To build, you need the same tools as HF Propagation:

- Android Studio, with SDK Platform 35, the NDK (Side by side) and CMake from the SDK Manager.
- **HamPropCore** in `Documents\HamPropCore`, next to the AndroidStudioProjects folder. The CW decoder is compiled from
  there.

To build from a terminal, set `JAVA_HOME` to Studio's `jbr` folder, then run `gradlew assembleDebug`.

## Using it with the IC-705

1. Connect the radio's USB-C socket to the phone (a USB-C to USB-C lead or an OTG adapter).
2. Open a mode, allow audio recording, and tune the radio to that mode's frequency in USB-D.
3. The page's waterfall shows the radio's audio. The level is set on the radio under MENU > SET > Connectors > USB AF/SQL.

# Development commands (debug builds only)

## Decoder check on the phone

This runs the native decoders on recordings and writes the results to logcat under the tag `HDTEST`. It checks the
decoders on the phone's own processor (ARM), with no radio needed.

```
adb push <wav> /data/local/tmp/
adb shell run-as uk.hamdigital mkdir -p files/test/ft8      # also ft4, js8, wspr
adb shell run-as uk.hamdigital cp /data/local/tmp/<wav> files/test/ft8/
adb shell am start -n uk.hamdigital/.MainActivity --ez dev_test true
adb logcat -s HDTEST
```

Shared storage (/sdcard/Android/data/...) cannot be used: the app cannot read folders that adb created there.

The recordings must be 12 kHz, 16-bit, mono. Test sets: ft8_lib's `test/wav`, and WSJT-X's `samples/FT4`,
`samples/FT8` and `samples/WSPR`. JS8Call's `media/tests` has the JS8 recordings.

Other folders the check reads:

- `files/test/adif/*.adi`: an ADIF file is read, written and read again (the same?).
- `files/test/sstv/` (any file, even empty): each SSTV mode is encoded and decoded again.
- `files/test/freedv/*.raw`: 8 kHz 16-bit speech (e.g. codec2's `raw/hts1a.raw`) is sent through each FreeDV mode,
  RADE included, with noise at 5 dB, and received again; the callsign M7JVY goes in the text / end-of-over frame.
- `files/test/kb/` (any file): RTTY, PSK31 / 63 / 125 and each Olivia setting sent and received through the JNI.
- `files/test/wefax/*.wav`: an 11025 Hz weather-fax recording (tools/test's `wefax_broadcast.wav`): states, the chart kept.
- `files/test/rade/*.wav`: an 8 kHz RADE recording is decoded as the FreeDV page does it: callsigns, frames in sync,
  and the time taken as a share of real time. rade_c's `FDV_offair.wav` is 48 kHz: resample it to 8 kHz first.

## PC tests

`tools/test/run_*.sh` build each decoder with `zig cc` (set `ZIG` to zig.exe) and run it on a PC. For RADE:
`run_rade.sh rx <8 kHz WAV>` decodes a recording, showing each callsign; `run_rade.sh tx <16 kHz speech WAV> <call>`
sends speech and a callsign, adds noise at several SNRs and receives it again.

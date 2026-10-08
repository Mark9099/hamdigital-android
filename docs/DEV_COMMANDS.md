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

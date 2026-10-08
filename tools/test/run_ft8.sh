#!/bin/sh
# Build and run the PC test of the FT8 decoder (test_ft8.c) on ft8_lib's test recordings.
# Needs a C compiler: ZIG = path to zig.exe (zig cc), or CC. Recordings: WAVS = ft8_lib's test/wav folder.
set -e                                               # stop on an error
HERE=$(cd "$(dirname "$0")" && pwd)                  # tools/test
CPP="$HERE/../../app/src/main/cpp"                   # the app's native code
CC=${CC:-"$ZIG cc"}                                  # the compiler
OUT=${OUT:-"$HERE/build"}; mkdir -p "$OUT"           # build folder
$CC -O2 -w -include "$HERE/win_compat.h" -I"$CPP" -I"$CPP/ft8_lib" -o "$OUT/test_ft8.exe" "$HERE/test_ft8.c" "$CPP/ft8_slot.c" \
    "$CPP"/ft8_lib/ft8/*.c "$CPP"/ft8_lib/fft/*.c "$CPP/ft8_lib/common/monitor.c" -lm # same sources as the app
"$OUT/test_ft8.exe" "$WAVS"/*.wav                     # decode them all

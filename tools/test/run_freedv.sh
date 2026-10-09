#!/bin/sh
# Build and run the PC test of the FreeDV build (test_freedv.c) on codec2's speech sample (RAW: e.g. codec2/raw/hts1a.raw).
# ZIG = path to zig.exe (zig cc), or CC.
set -e                                               # stop on an error
HERE=$(cd "$(dirname "$0")" && pwd)                  # tools/test
C2="$HERE/../../app/src/main/cpp/codec2"             # the app's copy of codec2
CC=${CC:-"$ZIG cc"}                                  # the compiler
OUT=${OUT:-"$HERE/build"}; mkdir -p "$OUT"           # build folder
$CC -O2 -w -D_USE_MATH_DEFINES -DGIT_HASH=\"310777b\" -I"$C2" -o "$OUT/test_freedv.exe" "$HERE/test_freedv.c" "$C2"/*.c -lm
"$OUT/test_freedv.exe" "$RAW"

#!/bin/sh
# Build and run the PC test of the WSPR decoder (test_wspr.c) on WSPR recordings (WAVS: a folder of yymmdd_hhmm.wav).
# Needs a C compiler: ZIG = path to zig.exe (zig cc), or CC.
set -e                                               # stop on an error
HERE=$(cd "$(dirname "$0")" && pwd)                  # tools/test
CPP="$HERE/../../app/src/main/cpp"                   # the app's native code
CC=${CC:-"$ZIG cc"}                                  # the compiler
OUT=${OUT:-"$HERE/build"}; mkdir -p "$OUT/wsprdata"  # build folder, wsprd's data folder
W="$CPP/wsprd"                                       # wsprd's sources
$CC -O2 -w -include "$HERE/win_compat.h" -I"$CPP" -I"$CPP/fftw_kiss" -I"$CPP/ft8_lib" -c "$W/wsprd.c" -Dmain=wsprd_main -o "$OUT/wsprd.o" # its main() renamed, as the app
$CC -O2 -w -include "$HERE/win_compat.h" -I"$CPP" -I"$CPP/fftw_kiss" -I"$CPP/ft8_lib" -o "$OUT/test_wspr.exe" "$HERE/test_wspr.c" "$CPP/wspr_run.c" \
    "$OUT/wsprd.o" "$W/wsprd_utils.c" "$W/wsprsim_utils.c" "$W/fano.c" "$W/jelinek.c" "$W/nhash.c" "$W/tab.c" \
    "$CPP/fftw_kiss/fftw_kiss.c" "$CPP"/ft8_lib/fft/kiss_fft.c "$CPP"/ft8_lib/fft/kiss_fftr.c -lm # same sources as the app
"$OUT/test_wspr.exe" "$OUT/wsprdata" "$WAVS"/*.wav    # decode them all

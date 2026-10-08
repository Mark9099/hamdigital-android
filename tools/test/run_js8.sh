#!/bin/sh
# Build and run the PC test of the JS8 decoder (test_js8.cpp) on JS8Call's test recordings (WAVS: its media/tests).
# Needs a C++20 compiler: ZIG = path to zig.exe (zig c++), or CXX. The word table (jsc_map.cpp) takes a while.
set -e                                               # stop on an error
HERE=$(cd "$(dirname "$0")" && pwd)                  # tools/test
CPP="$HERE/../../app/src/main/cpp"                   # the app's native code
J="$CPP/js8"                                         # the JS8 code
CXX=${CXX:-"$ZIG c++"}; CC=${CC:-"$ZIG cc"}          # the compilers
OUT=${OUT:-"$HERE/build"}; mkdir -p "$OUT"           # build folder
[ -f "$OUT/jsc_map.o" ] || $CXX -O1 -w -c "$J/jsc_map.cpp" -I"$J" -o "$OUT/jsc_map.o" # (once: 262144 table entries)
$CC -O2 -w -c -I"$CPP/ft8_lib" "$CPP/ft8_lib/ft8/crc.c" -o "$OUT/ft8crc.o"            # the reference CRC
$CC -O2 -w -c -I"$CPP/ft8_lib" -I"$CPP/fftw_kiss" "$CPP/fftw_kiss/fftw_kiss.c" -o "$OUT/fftw_kiss.o"
$CC -O2 -w -c -I"$CPP/ft8_lib" "$CPP/ft8_lib/fft/kiss_fft.c" -o "$OUT/kiss_fft.o"
$CC -O2 -w -c -I"$CPP/ft8_lib" "$CPP/ft8_lib/fft/kiss_fftr.c" -o "$OUT/kiss_fftr.o"
$CXX -std=c++20 -O2 -w -D_USE_MATH_DEFINES -DEIGEN_DONT_VECTORIZE -I"$J" -I"$CPP/fftw_kiss" -I"$CPP/ft8_lib" -o "$OUT/test_js8.exe" "$HERE/test_js8.cpp" \
    "$J/JS8.cpp" "$J/js8_run.cpp" "$J/js8_unpack.cpp" "$OUT/jsc_map.o" "$OUT/ft8crc.o" "$OUT/fftw_kiss.o" "$OUT/kiss_fft.o" "$OUT/kiss_fftr.o"
"$OUT/test_js8.exe" "$WAVS"/*.wav

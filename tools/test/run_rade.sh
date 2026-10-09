#!/bin/sh
# Build and run the PC test of the FreeDV RADE build (test_rade.c). ZIG = path to zig.exe (zig cc), or CC.
#   run_rade.sh rx <8 kHz WAV>                      decode a recording (e.g. rade_c's FDV_offair.wav, resampled to 8 kHz)
#   run_rade.sh tx <16 kHz speech WAV> <callsign>   round trip with noise (e.g. rade_c's input_sample.wav)
set -e                                               # stop on an error
HERE=$(cd "$(dirname "$0")" && pwd)                  # tools/test
R="$HERE/../../app/src/main/cpp/rade"                # the app's copy of RADE + the Opus parts it uses
CC=${CC:-"$ZIG cc"}                                  # the compiler
OUT=${OUT:-"$HERE/build/rade"}; mkdir -p "$OUT"      # build folder (objects kept: the weights take a while)
INC="-I$R -I$R/src -I$R/opus -I$R/opus/include -I$R/opus/celt -I$R/opus/dnn"
FLAGS="-O2 -w -DHAVE_CONFIG_H -D_USE_MATH_DEFINES -DIS_BUILDING_RADE_API=1"
for f in "$R"/src/*.c "$R"/opus/celt/*.c "$R"/opus/dnn/*.c "$R"/*.c; do # each source, once
    o="$OUT/$(basename "$f" .c).o"
    [ "$o" -nt "$f" ] || $CC -c $FLAGS $INC -o "$o" "$f"
done
$CC $FLAGS $INC -o "$OUT/test_rade.exe" "$HERE/test_rade.c" "$OUT"/*.o -lm
"$OUT/test_rade.exe" "$@"

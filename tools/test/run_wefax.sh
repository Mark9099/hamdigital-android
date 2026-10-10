#!/bin/sh
# Build and run the PC test of the weather-fax receiver (test_wefax.cxx) on a generated fax broadcast; writes
# wefax_<snr>.png into the build folder. ZIG = path to zig.exe (zig c++), or CXX.
set -e                                               # stop on an error
HERE=$(cd "$(dirname "$0")" && pwd)                  # tools/test
F="$HERE/../../app/src/main/cpp/fldigi"              # the app's fldigi code
CXX=${CXX:-"$ZIG c++"}                               # the compiler
OUT=${OUT:-"$HERE/build"}; mkdir -p "$OUT"           # build folder
$CXX -O2 -w -I"$F" -o "$OUT/test_wefax.exe" "$HERE/test_wefax.cxx" "$F/wefax_rx.cxx" "$F/filters.cxx" "$F/fftfilt.cxx" # same sources as the app
cd "$OUT" && ./test_wefax.exe

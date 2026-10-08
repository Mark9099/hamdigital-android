#!/bin/sh
# Build and run the PC test of the RTTY and PSK31 receivers (test_fldigi.cxx) on generated signals.
# Needs a C++ compiler: ZIG = path to zig.exe (zig c++), or CXX.
set -e                                               # stop on an error
HERE=$(cd "$(dirname "$0")" && pwd)                  # tools/test
F="$HERE/../../app/src/main/cpp/fldigi"              # the app's fldigi code
CXX=${CXX:-"$ZIG c++"}                               # the compiler
OUT=${OUT:-"$HERE/build"}; mkdir -p "$OUT"           # build folder
$CXX -O2 -w -I"$F" -o "$OUT/test_fldigi.exe" "$HERE/test_fldigi.cxx" "$F/rtty_rx.cxx" "$F/psk31_rx.cxx" \
    "$F/fftfilt.cxx" "$F/filters.cxx" "$F/pskcoeff.cxx" "$F/pskvaricode.cxx" # same sources as the app
"$OUT/test_fldigi.exe"

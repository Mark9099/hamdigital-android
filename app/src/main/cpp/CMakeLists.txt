# Native part of the app: the decoders, all open-source C/C++ code, and the JNI bridges to them.
#  - CW: the Morse decoder shared with HF Propagation and the Tab5 (HamPropCore hamprop_common/src/cw_decoder.cpp).
#  - FT8 / FT4: ft8_lib (MIT). WSPR: wsprd from WSJT-X (GPL v3). RTTY / PSK31: fldigi's demodulators (GPL v3).
#  - JS8: JS8Call's decoder and message unpacking (GPL v3), without Qt / Boost (js8/ANDROID_CHANGES.txt).
#  - fftw_kiss: FFTW's API on KISS FFT, for wsprd and JS8. Each library's folder has its LICENSE and ANDROID_CHANGES.txt.
#  - FreeDV: codec2 (LGPL 2.1), a static library of its own (below).
cmake_minimum_required(VERSION 3.22.1)
project(hamdigital C CXX)                             # C and C++ decoders

# HamPropCore: a sibling of the AndroidStudioProjects folder (Documents\HamPropCore, its own git repository); another
# place can be given with -DHAMPROP_CORE=... (externalNativeBuild arguments).
set(HAMPROP_CORE "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../../HamPropCore" CACHE PATH "HamPropCore folder")
get_filename_component(HAMPROP_CORE "${HAMPROP_CORE}" ABSOLUTE) # tidy the path
if(NOT EXISTS "${HAMPROP_CORE}/hamprop_common/src/cw_decoder.cpp")
    message(FATAL_ERROR "HamPropCore not found at ${HAMPROP_CORE}: clone it next to the AndroidStudioProjects folder")
endif()

file(GLOB FT8_SOURCES CONFIGURE_DEPENDS ft8_lib/ft8/*.c ft8_lib/fft/*.c ft8_lib/common/monitor.c) # ft8_lib (MIT): FT8 / FT4

set(WSPR_SOURCES wsprd/wsprd.c wsprd/wsprd_utils.c wsprd/wsprsim_utils.c wsprd/fano.c wsprd/jelinek.c wsprd/nhash.c wsprd/tab.c) # wsprd from WSJT-X (GPL v3); metric_tables.c is included by wsprd.c
set_source_files_properties(wsprd/wsprd.c PROPERTIES COMPILE_DEFINITIONS "main=wsprd_main") # its main() becomes a callable function

set(FLDIGI_SOURCES fldigi/rtty_rx.cxx fldigi/psk31_rx.cxx fldigi/kb_tx.cxx fldigi/fftfilt.cxx fldigi/filters.cxx fldigi/pskcoeff.cxx fldigi/pskvaricode.cxx) # fldigi (GPL v3): receive-only RTTY / PSK31 + its filters
set_source_files_properties(${FLDIGI_SOURCES} PROPERTIES INCLUDE_DIRECTORIES ${CMAKE_CURRENT_SOURCE_DIR}/fldigi) # its headers by plain name

set(JS8_SOURCES js8/JS8.cpp js8/js8_run.cpp js8/js8_unpack.cpp js8/js8_pack.cpp) # JS8Call's decoder (GPL v3; C++20, Eigen) + its message unpacking
set_source_files_properties(${JS8_SOURCES} js8_jni.cpp PROPERTIES COMPILE_OPTIONS "-std=c++20;-w" INCLUDE_DIRECTORIES "${CMAKE_CURRENT_SOURCE_DIR}/js8") # <vendor/Eigen/Dense>, commons.h
set_source_files_properties(js8/jsc_map.cpp PROPERTIES COMPILE_OPTIONS "-w;-O0" INCLUDE_DIRECTORIES "${CMAKE_CURRENT_SOURCE_DIR}/js8") # the JSC word table (262144 entries: nothing to optimise, much faster to build)

# codec2 (LGPL 2.1; drowe67/codec2 at 310777b): FreeDV's modems and the speech codec, built as its own static library.
# Its codebook tables are generated once on a PC (its generate_codebook) and kept in codec2/ (codec2/ANDROID_CHANGES.txt).
# It carries its own KISS FFT, as ft8_lib does: its functions are renamed here so the two copies cannot be mixed up.
file(GLOB CODEC2_SOURCES CONFIGURE_DEPENDS codec2/*.c)
add_library(codec2_hd STATIC ${CODEC2_SOURCES})
target_compile_definitions(codec2_hd PRIVATE GIT_HASH="310777b"
    kiss_fft_alloc=c2_kiss_fft_alloc kiss_fft_stride=c2_kiss_fft_stride kiss_fft=c2_kiss_fft kiss_fft_cleanup=c2_kiss_fft_cleanup
    kiss_fft_next_fast_size=c2_kiss_fft_next_fast_size kiss_fftr_alloc=c2_kiss_fftr_alloc kiss_fftr=c2_kiss_fftr kiss_fftri=c2_kiss_fftri
    encode=c2_ldpc_encode)                           # (its LDPC encode, as wsprd's fano.c has an encode)
target_compile_options(codec2_hd PRIVATE -O2 -w)     # optimised; upstream's warnings are not ours to change
target_include_directories(codec2_hd PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/codec2) # (<codec2/version.h> is in codec2/codec2/)
set_source_files_properties(freedv_jni.c PROPERTIES INCLUDE_DIRECTORIES ${CMAKE_CURRENT_SOURCE_DIR}/codec2) # codec2's headers for the bridge alone (theirs and ft8_lib's share names)

add_library(hamdigital SHARED
    ${HAMPROP_CORE}/hamprop_common/src/cw_decoder.cpp # CW decoder (shared)
    cw_jni.cpp                                       # CW decoder: JNI
    ${FT8_SOURCES}                                   # FT8 / FT4
    ft8_slot.c                                       # FT8 / FT4: a slot at a time
    ft8_jni.c                                        # FT8 / FT4: JNI
    fftw_kiss/fftw_kiss.c                            # FFTW's API on KISS FFT (for wsprd and JS8)
    ${WSPR_SOURCES}                                  # WSPR
    wspr_run.c                                       # WSPR: a slot at a time
    wspr_jni.c                                       # WSPR: JNI
    ${FLDIGI_SOURCES}                                # RTTY, PSK31
    fldigi_jni.cpp                                   # RTTY, PSK31: JNI
    ${JS8_SOURCES}                                   # JS8
    js8/jsc_map.cpp                                  # JS8: the JSC word table
    js8_jni.cpp                                      # JS8: JNI
    freedv_jni.c)                                    # FreeDV (codec2): JNI

target_include_directories(hamdigital PRIVATE ${HAMPROP_CORE}/hamprop_common/src ${CMAKE_CURRENT_SOURCE_DIR} ${CMAKE_CURRENT_SOURCE_DIR}/ft8_lib
    ${CMAKE_CURRENT_SOURCE_DIR}/fftw_kiss)           # <fftw3.h> is the stand-in
set_source_files_properties(${FT8_SOURCES} ${WSPR_SOURCES} ${FLDIGI_SOURCES} PROPERTIES COMPILE_OPTIONS "-w") # upstream's warnings are not ours to change
target_compile_options(hamdigital PRIVATE -O2)       # optimised (the decoders run in real time)
target_link_libraries(hamdigital codec2_hd log m)    # FreeDV, logcat, maths

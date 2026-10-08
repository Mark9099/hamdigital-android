# Native part of the app: the decoders, all open-source C/C++ code, and the JNI bridges to them.
#  - CW: the Morse decoder shared with HF Propagation and the Tab5 (HamPropCore hamprop_common/src/cw_decoder.cpp).
#  - Later stages add ft8_lib (FT8 / FT4), wsprd (WSPR), the JS8Call decoder and fldigi's RTTY and PSK31 (docs/PLAN.md).
cmake_minimum_required(VERSION 3.22.1)
project(hamdigital C CXX)                             # C and C++ decoders

# HamPropCore: a sibling of the AndroidStudioProjects folder (Documents\HamPropCore, its own git repository); another
# place can be given with -DHAMPROP_CORE=... (externalNativeBuild arguments).
set(HAMPROP_CORE "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../../HamPropCore" CACHE PATH "HamPropCore folder")
get_filename_component(HAMPROP_CORE "${HAMPROP_CORE}" ABSOLUTE) # tidy the path
if(NOT EXISTS "${HAMPROP_CORE}/hamprop_common/src/cw_decoder.cpp")
    message(FATAL_ERROR "HamPropCore not found at ${HAMPROP_CORE}: clone it next to the AndroidStudioProjects folder")
endif()

add_library(hamdigital SHARED
    ${HAMPROP_CORE}/hamprop_common/src/cw_decoder.cpp # CW decoder (shared)
    cw_jni.cpp)                                      # CW decoder: JNI

target_include_directories(hamdigital PRIVATE ${HAMPROP_CORE}/hamprop_common/src ${CMAKE_CURRENT_SOURCE_DIR})
target_compile_options(hamdigital PRIVATE -O2)       # optimised (the decoders run in real time)
target_link_libraries(hamdigital log m)              # logcat, maths

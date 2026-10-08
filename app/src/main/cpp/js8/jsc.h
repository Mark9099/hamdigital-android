// JS8Call's JSC word table, receive side: only the map from code index to word that decompression needs (jsc_map.cpp,
// copied unchanged from JS8Call). JS8Call's own jsc.h also declares the compression functions and other tables, which
// use Qt; this keeps the same Tuple and JSC::map declarations so jsc_map.cpp compiles as it is. (HF Digital Modes)
//
// This file is part of JS8Call. (C) 2018 Jordan Sherer <kn4crd@gmail.com> - All Rights Reserved. Distributed under the
// GNU General Public License v3 (LICENSE).
#pragma once
#include <cstdint>                                   // uint32_t

typedef struct Tuple {
    char const *str;                                 // the word (Latin-1)
    int size;                                        // its length
    int index;                                       // its code index
} Tuple;

class JSC {
public:
    static const std::uint32_t size = 262144;        // entries
    static const Tuple map[262144];                  // code index -> word (jsc_map.cpp)
};

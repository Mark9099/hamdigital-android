// JS8 slot decoder (js8_run.cpp, on JS8Call's decoder): used by the app through js8_jni.cpp and by the PC test.
#pragma once
#include <cstdint>                                   // int16_t
#include <string>
#include <vector>

struct Js8Line {                                     // one decoded frame
    int snr;                                         // dB
    float dt;                                        // time offset (s)
    float freq;                                      // audio frequency (Hz)
    int bits;                                        // frame-type bits (first / last / data)
    bool lowConfidence;                              // JS8Call shows these in [brackets]
    std::string frame;                               // the 12 characters on the air
    int frameType;                                   // js8unpack::FrameType
    std::string from, to;                            // calls, where the frame carries them
    std::string message;                             // as JS8Call shows it (UTF-8)
};

/** Decode one JS8 Normal (15 s) slot: n samples of 12 kHz mono audio from the slot's start; nfqso is the receive
 *  offset (Hz) the decoder tries first. */
std::vector<Js8Line> js8_decode_slot(const int16_t *samples, int n, int nfqso);

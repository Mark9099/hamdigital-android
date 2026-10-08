// JS8 slot decoder: JS8Call's decoder (JS8.cpp's Engine, the decoding pass of JS8Call's Worker) run on one 15-second
// JS8 Normal slot, the way JS8Call's main window schedules a full-cycle decode (kposA = the cycle's start, kszA = its
// length, submode A), with each frame unpacked into text as JS8Call's DecodedText does. (HF Digital Modes)
#include "js8_run.h"                                 // this file's API
#include "JS8.hpp"                                   // the decoder
#include "js8_unpack.h"                              // frame -> text
#include <algorithm>                                 // std::copy
#include <cstring>                                   // memset
#include <memory>                                    // unique_ptr
#include <mutex>                                     // one decode at a time
#include <variant>                                   // the decoder's events

// The decoder's shared data, as JS8Call defines them (commons.h declares them).
struct dec_data dec_data;                            // the 60 s sample buffer and the decode parameters
struct specData specData;                            // (spectrum averages; filled by the decoder)
std::mutex fftw_mutex;                               // FFT plan creation lock (the decoder takes it)

static constexpr float QUALITY_THRESHOLD = 0.17f;    // DecodedText: below this a decode is suspect

std::vector<Js8Line> js8_decode_slot(const int16_t *samples, int n, int nfqso)
{
    static std::mutex mx; std::lock_guard<std::mutex> lock(mx); // one at a time (shared data)
    static std::unique_ptr<JS8::Engine> engine;      // made once: its FFT plans take a while
    if (!engine) engine = std::make_unique<JS8::Engine>(dec_data);

    int const cycle = 15 * JS8_RX_SAMPLE_RATE;       // JS8 Normal: 15 s
    if (n > cycle) n = cycle;
    std::memset(dec_data.d2, 0, sizeof dec_data.d2); // the slot at the buffer's start
    std::copy(samples, samples + n, dec_data.d2);
    auto &p = dec_data.params;
    p.nutc = 0;                                      // (the app keeps the slot's time itself)
    p.nfqso = nfqso;                                 // the offset tried first
    p.newdat = true;                                 // new data: the long FFT
    p.nfa = 0; p.nfb = 5000;                         // the decode range (Hz), as JS8Call with its filter off
    p.syncStats = false;                             // no sync-candidate events
    p.kin = n;                                       // samples written
    p.kposA = 0; p.kszA = cycle;                     // submode A: the whole cycle
    p.kposB = p.kszB = p.kposC = p.kszC = p.kposE = p.kszE = p.kposI = p.kszI = 0;
    p.nsubmodes = 1;                                 // A (Normal) only

    std::vector<Js8Line> out;
    engine->decode([&out](JS8::Event::Variant const &ev) {
        if (auto const *d = std::get_if<JS8::Event::Decoded>(&ev)) { // a frame
            Js8Line l;
            l.snr = d->snr; l.dt = d->xdt; l.freq = d->frequency; l.bits = d->type;
            l.lowConfidence = d->quality < QUALITY_THRESHOLD;
            l.frame = d->data;
            js8unpack::Unpacked u = js8unpack::unpack(d->data, d->type); // its text
            l.frameType = u.frameType; l.from = u.from; l.to = u.to; l.message = u.message;
            out.push_back(l);
        }
    });
    return out;
}

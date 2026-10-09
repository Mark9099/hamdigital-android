// Goertzel tone detectors for the shared CW decoder (cw_decoder.h) - from the Tab5CWDecoder project.
#pragma once

#include <array>   // for std::array, used by the Hann window tables below
#include <cmath>   // for cosf/M_PI (window coefficients) and the trig already used elsewhere in this file

#ifndef M_PI
#define M_PI 3.14159265358979323846  // not standard C++: provided where the platform lacks it
#endif

// Precomputed Hann window coefficients (0.5 - 0.5*cos(2*pi*i/(N-1))),
// tapering each Goertzel window's samples toward zero at its edges
// instead of a hard rectangular cutoff. A rectangular window's abrupt
// edges leak energy into neighbouring frequencies (spectral leakage),
// letting a strong tone or broadband click partially bleed into
// adjacent bins/candidate frequencies; Hann tapering suppresses that at
// the cost of a slightly wider main lobe, a standard, well-established
// tradeoff for this kind of narrowband detection. Computed once, lazily,
// via a function-local static (C++11 guarantees this initializes exactly
// once even though every GoertzelBin/FastGoertzelBin instance calls it).
// Note: windowing reduces the *coherent gain* of the filter, so
// power() reads proportionally smaller than an unwindowed Goertzel would
// -- harmless here since every consumer of power() only ever compares it
// relatively (ratios, EMA-tracked floor/peak), never against an absolute
// constant.
inline const float* hannTable512() {                                  // returns the 512-point Hann table used by GoertzelBin
    static const auto table = [] {                                    // function-local static: built exactly once, thread-safety guaranteed by the standard
        std::array<float, 512> t{};                                   // 512 coefficients, one per sample position in the slow bin's window
        for (int i = 0; i < 512; ++i) {                                // fill every position in the window
            t[i] = 0.5f - 0.5f * cosf(2.0f * (float)M_PI * i / 511.0f);  // standard Hann formula, N-1=511 denominator
        }
        return t;                                                     // hand the filled table back to the static initializer
    }();
    return table.data();                                               // expose as a raw pointer for the hot feed() loop
}

inline const float* hannTable64() {                                   // returns the 64-point Hann table used by FastGoertzelBin
    static const auto table = [] {                                    // same once-only lazy-init pattern as hannTable512()
        std::array<float, 64> t{};                                     // 64 coefficients, one per sample position in the fast bin's window
        for (int i = 0; i < 64; ++i) {                                  // fill every position in the window
            t[i] = 0.5f - 0.5f * cosf(2.0f * (float)M_PI * i / 63.0f);  // standard Hann formula, N-1=63 denominator
        }
        return t;                                                     // hand the filled table back to the static initializer
    }();
    return table.data();                                               // expose as a raw pointer for the hot feed() loop
}

// Single-pole DC-blocking high-pass filter (y[n] = x[n] - x[n-1] +
// R*y[n-1]), the standard first stage of almost any audio DSP pipeline.
// The mic ADC's raw samples can carry a small, slowly-wandering DC bias;
// left in, that bias adds a constant term to every Goertzel window's
// energy that has nothing to do with the 700 Hz tone, nudging the
// measured power (and hence the noise floor / squelch decisions) around
// for reasons unrelated to the actual signal. R=0.995 puts the filter's
// cutoff at a few Hz -- far below the lowest CW audio tone anyone would
// use, so it has no effect on the tone or its keying envelope, only on
// the true DC term.
class DcBlocker {
   public:
    inline float process(float x) {
        float y = x - _prevIn + 0.995f * _prevOut;
        _prevIn  = x;
        _prevOut = y;
        return y;
    }

   private:
    float _prevIn  = 0.0f;
    float _prevOut = 0.0f;
};

// Narrowband power (energy) detector for one candidate CW tone frequency,
// built from several time-staggered Goertzel resonators run in parallel.
//
// A single non-overlapping Goertzel window has a fixed time/frequency
// tradeoff: bandwidth is roughly 2x the sample rate divided by the window
// length, so a short window (needed for fast timing updates) is also a
// wide, poorly-selective filter. This project's early wide-bandwidth
// windows (128 samples @ 16kHz, ~250Hz bandwidth vs. 50Hz spacing between
// candidate frequencies) let adjacent bins bleed into each other and let
// broadband noise/clicks masquerade as tone -- a real, established CW
// decoder (the WB7FHC/VK2IDL design; see docs/PROJECT_LOG.md) avoids this
// by using a much longer window (~75Hz bandwidth) while still updating
// every few milliseconds, via several such windows running in parallel,
// each started a bit later than the last. This is the same technique,
// tuned for this project's much more capable hardware: a 512-sample
// window (~62Hz bandwidth, narrower than that reference) split into 4
// staggered instances, so a fresh reading is still available every 128
// samples (8ms) -- the same update cadence as the original single-window
// design, with meaningfully better frequency selectivity.
class GoertzelBin {
   public:
    void init(float targetFreqHz, float sampleRateHz) {
        _freq  = targetFreqHz;
        _coeff = 2.0f * cosf(2.0f * (float)M_PI * targetFreqHz / sampleRateHz);
        for (int p = 0; p < kOverlap; ++p) {
            _phase[p].q1    = 0.0f;
            _phase[p].q2    = 0.0f;
            _phase[p].count = p * kStagger;  // staggered starting offsets
        }
        _lastPower = 0.0f;
    }

    // Call once per raw audio sample. Internally updates whichever
    // staggered instance is due; every kStagger samples, exactly one
    // instance completes its kWindow-sample integration and refreshes
    // power().
    inline void feed(float sample) {
        const float* hann = hannTable512();               // shared 512-point Hann table for this window size
        for (int p = 0; p < kOverlap; ++p) {               // update every staggered phase with this one new sample
            Phase& ph = _phase[p];                          // the staggered instance due for this update
            int idx        = ph.count % kWindow;            // position within this phase's own window (mod guards the initial stagger offset)
            float windowed = sample * hann[idx];             // taper this sample toward zero near the window's edges
            float q0  = _coeff * ph.q1 - ph.q2 + windowed;   // standard Goertzel recursion step, fed the windowed sample
            ph.q2     = ph.q1;                                // shift state: q2 <- old q1
            ph.q1     = q0;                                   // shift state: q1 <- new q0
            if (++ph.count >= kWindow) {                      // this phase's window just completed
                _lastPower = ph.q1 * ph.q1 + ph.q2 * ph.q2 - _coeff * ph.q1 * ph.q2;  // Goertzel power formula
                ph.q1 = ph.q2 = 0.0f;                          // reset recursion state for the next window
                ph.count      = 0;                             // restart this phase's position counter
            }
        }
    }

    // Most recently completed window's energy at this bin's frequency.
    float power() const { return _lastPower; }
    float frequency() const { return _freq; }

   private:
    static constexpr int kOverlap = 4;          // parallel staggered instances
    static constexpr int kWindow  = 512;        // samples integrated per reading (~62Hz bandwidth @16kHz)
    static constexpr int kStagger = kWindow / kOverlap;  // samples between instances completing (128 = 8ms @16kHz)

    struct Phase {
        float q1 = 0.0f, q2 = 0.0f;
        int count = 0;
    };

    float _freq  = 0.0f;
    float _coeff = 0.0f;
    Phase _phase[kOverlap];
    float _lastPower = 0.0f;
};

// Small, fast, single-window Goertzel resonator retuned to whatever
// frequency is *already known* to be the locked tone, used only for the
// on/off envelope (timing) decision. A direct raw-waveform capture from
// this project's real test source showed its inter-element gaps are
// brief (~15-20ms) and only partially dip in amplitude, not true
// silence -- a window as long as GoertzelBin's (32ms, needed for its
// frequency selectivity) smooths right over a gap that short, diluting
// it with the tone on either side rather than showing it. Once the
// frequency is known, fine frequency selectivity is no longer needed
// here, so a much shorter window can be used instead, sharp enough to
// resolve a 15-20ms feature clearly.
class FastGoertzelBin {
   public:
    static constexpr int kWindow = 64;  // ~4ms @16kHz

    void init(float targetFreqHz, float sampleRateHz) {
        _coeff     = 2.0f * cosf(2.0f * (float)M_PI * targetFreqHz / sampleRateHz);
        _q1 = _q2  = 0.0f;
        _count     = 0;
        _lastPower = 0.0f;
    }

    inline void feed(float sample) {
        const float* hann = hannTable64();          // shared 64-point Hann table for this window size
        int idx           = _count % kWindow;        // position within the current window (mod guards startup edge cases)
        float windowed    = sample * hann[idx];       // taper this sample toward zero near the window's edges
        float q0 = _coeff * _q1 - _q2 + windowed;      // standard Goertzel recursion step, fed the windowed sample
        _q2      = _q1;                                // shift state: q2 <- old q1
        _q1      = q0;                                 // shift state: q1 <- new q0
        if (++_count >= kWindow) {                      // this window just completed
            _lastPower = _q1 * _q1 + _q2 * _q2 - _coeff * _q1 * _q2;  // Goertzel power formula
            _q1 = _q2 = 0.0f;                            // reset recursion state for the next window
            _count     = 0;                              // restart the position counter
        }
    }

    float power() const { return _lastPower; }

   private:
    float _coeff = 0.0f;
    float _q1 = 0.0f, _q2 = 0.0f;
    int _count       = 0;
    float _lastPower = 0.0f;
};

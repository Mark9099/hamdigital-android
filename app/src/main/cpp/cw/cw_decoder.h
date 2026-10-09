// Shared CW (Morse) decoder engine for HF Propagation (Tab5 Tools > CW decoder, Android likewise) - from the
// Tab5CWDecoder project (v0.5.0, its docs/PROJECT_LOG.md has the tuning history). Changes for sharing: the clock is the
// audio itself (samples fed, 16 kHz) instead of esp_timer, so it runs the same on the ESP32-P4 and Android and has no
// scheduling jitter; the diagnostic printf lines only print with CW_DEBUG defined. Feed 16 kHz mono 16-bit audio.
#pragma once

#include <cstdint>   // fixed-width integer types (int64_t for timestamps, int16_t for samples)
#include <string>    // std::string for the decoded text / in-progress symbol
#include <vector>    // std::vector for the per-element probability list

#include "cw_goertzel.h"    // GoertzelBin, FastGoertzelBin, DcBlocker
#include "cw_morse_table.h" // ElementProb, decodeMorseProbabilistic()

// Listens to a stream of 16-bit mono audio samples, finds the CW tone
// among a bank of candidate frequencies, and turns its on/off timing into
// decoded text plus a live words-per-minute estimate.
//
// Usage:
//   CwDecoder decoder;
//   decoder.begin();
//   ... on new audio ...
//   decoder.processAudioBlock(samples, count);
//   ... every loop iteration (even with no new audio) ...
//   decoder.tick();
class CwDecoder {
   public:
    void begin();  // resets every piece of decoder state to a fresh startup condition

    // Feed newly captured mono audio samples (any length; internally
    // chopped into fixed-size analysis blocks).
    void processAudioBlock(const int16_t* samples, size_t count);

    // Must be called regularly (e.g. once per main loop iteration) so a
    // trailing letter/word gets flushed to decodedText() even if the
    // operator stops sending and no more audio-driven edges occur.
    void tick();

    float wpm() const { return 1200.0f / _ditMs; }                        // standard WPM<->dit-length relationship (PARIS timing)
    float toneFrequencyHz() const { return _bins[_lockedBin].frequency(); }  // frequency of whichever bin is currently locked
    float signalLevel01() const { return _level01; }                       // 0..1 normalized envelope level, for the UI meter
    bool toneActive() const { return _toneOn; }                            // current debounced on/off state

    const std::string& decodedText() const { return _decodedText; }   // the full running transcript
    // In-progress letter's dot/dash string, for live display. Under
    // "buffered output" (see setBufferedOutput() below), reports the
    // last *completed* letter's pattern instead of updating element-by-
    // element, trading live feedback for a steadier display.
    const std::string& currentSymbol() const { return _bufferedOutput ? _lastFinalizedSymbol : _currentSymbol; }

    void clearText() {
        _decodedText.clear();          // wipe the running transcript
        _currentSymbol.clear();        // wipe the in-progress letter's display string
        _currentElementProbs.clear();  // wipe the in-progress letter's per-element confidence list
    }

    // --- Diagnostics, for a live "what is the mic hearing" view ---
    static constexpr int kNumBins = 14;  // 350..1000 Hz, 50 Hz steps

    int lockedBinIndex() const { return _lockedBin; }              // index of the bin currently believed to carry the tone
    int pendingBinIndex() const { return _pendingBin; }             // index of a candidate rival bin, if any
    int candidateStreak() const { return _candidateStreak; }        // how many consecutive blocks the rival bin has led by
    float binFrequency(int i) const { return _bins[i].frequency(); }  // frequency assigned to slow bin i
    float binPower(int i) const { return _lastPower[i]; }            // most recent power reading for slow bin i
    float noiseFloor() const { return _globalNoiseFloor; }           // slow-bank noise floor estimate, used for bin selection
    float signalPeak() const { return _fastPeak; }                   // fast-bin peak estimate, used for the on/off threshold span
    float onThreshold() const { return _lastOnThresh; }              // most recently computed "on" power threshold
    float offThreshold() const { return _lastOffThresh; }            // most recently computed "off" power threshold
    float ditMs() const { return _ditMs; }                           // current authoritative dit-length estimate, in ms
    float biasMs() const { return _bias; }                           // current envelope-decay/detector-lag compensation, in ms
    float lockedBinFastPower() const { return _fastBin.power(); }    // fast bin's most recent power reading
    float fastNoiseFloor() const { return _fastNoiseFloor; }         // fast bin's own noise floor estimate
    float sidebandRef() const { return _sidebandRef; }               // same-instant +-280Hz sideband reference level -- see processFastBlock()
    bool everLocked() const { return _everLocked; }                  // whether a real frequency lock has ever been achieved

    // --- Runtime controls, replicating morse1981's control/config panel
    // (0.5.0, full-UI-replica request -- see docs/PROJECT_LOG.md) ---

    // Default sensitivity slider position (0..1) -- chosen to reproduce
    // this project's own hard-won tuned thresholds (onFrac=0.35) rather
    // than morse1981's own 60% default; public so the UI layer can offer
    // it as the "Auto sensitivity" toggle's target instead of duplicating
    // the constant.
    static constexpr float kDefaultSensitivity01 = 0.82f;

    // "Auto tune": forces a fresh re-acquisition of the tone frequency,
    // as if starting over, without a full cold-boot recalibration (the
    // ambient noise floor is already known, so only a short settle is
    // needed before the next lock decision).
    void autoTune();

    // "Click the waterfall to tune": manually locks onto a specific
    // candidate bin immediately, bypassing the normal streak-based
    // switch requirement -- the human has already identified the tone
    // visually, so there's no need to wait and confirm it again.
    void forceLock(int binIndex);

    // "Reset speed": returns the dit estimate to startWpm() and forgets
    // the rolling timing history, the same "give up on the current
    // estimate and start over" action as a manual override on the
    // reference's own "Reset speed" button.
    void resetSpeed();

    // "Sensitivity" slider (0..1): remaps the on/off threshold fractions
    // via the same formula morse1981 itself uses, so the two projects'
    // sliders behave comparably.
    void setSensitivity01(float s);
    float sensitivity01() const { return _sensitivity01; }  // current slider position, 0..1

    // "Start speed": the WPM resetSpeed() returns to, and the WPM this
    // decoder is seeded at from begin().
    void setStartWpm(float wpm);
    float startWpm() const { return _startWpm; }  // current configured start speed, in WPM

    // "Follow the tone": when on (default), the decoder keeps re-locking
    // onto whichever candidate bin is currently strongest, the existing
    // behavior throughout this project. When off, once locked it stays
    // on that bin regardless of what any other bin does.
    void setFollowTone(bool v) { _followTone = v; }
    bool followTone() const { return _followTone; }

    // "Fast speed lock": when on, the rolling timing fit (see
    // fitTimingFromHistory()) blends each new fit result in much more
    // aggressively, converging to the sender's real speed in fewer
    // elements at the cost of being less smooth/stable once converged.
    void setFastSpeedLock(bool v) { _fastSpeedLock = v; }
    bool fastSpeedLock() const { return _fastSpeedLock; }

    // "Hold output until locked": when on (default), nothing is ever
    // appended to decodedText() before the first real frequency lock --
    // already this project's existing behavior (processFastBlock() does
    // nothing at all before _everLocked), so this mainly matters right
    // after autoTune() clears that flag and a fresh lock is pending.
    void setHoldUntilLocked(bool v) { _holdUntilLocked = v; }
    bool holdUntilLocked() const { return _holdUntilLocked; }

    // "Buffered output": when on, currentSymbol() (the live in-progress
    // dot/dash display) reports nothing until the letter it belongs to
    // actually finalizes, rather than updating element-by-element as
    // marks arrive -- trading live feedback for a steadier display.
    void setBufferedOutput(bool v) { _bufferedOutput = v; }
    bool bufferedOutput() const { return _bufferedOutput; }

    // Status meters, matching the reference's own "Signal strength (dB)"
    // readout: a rough SNR proxy from the fast bin's own tracked peak
    // and floor, in dB.
    float signalSnrDb() const;
    float peakToFloorDb() const;  // the fast bin's tracked peak against its floor (the old meter figure; diagnostics)

   private:
    static constexpr float kSampleRateHz = 16000.0f;  // fixed audio sample rate this whole pipeline assumes
    static constexpr int kBlockSize      = 128;        // 8 ms per bin-selection analysis block
    static constexpr int kBinSwitchBlocks = 25;         // ~200 ms a rival bin must lead by during ordinary passive background tracking
    // Shorter confirmation streak used only right after autoTune() (see
    // _reacquiring below), not for ordinary background tracking. The
    // full kBinSwitchBlocks window exists to stop a stray noise burst
    // from stealing an already-good lock mid-session, which matters a
    // lot during passive tracking -- but immediately after the user
    // explicitly presses "Auto tune," they've already identified a
    // clear tone and are waiting on the decoder to catch up, so the
    // same caution just reads as sluggishness. Still requires several
    // consecutive blocks to agree (not an instant grab, unlike
    // forceLock()), just a shorter one.
    static constexpr int kReacquireSwitchBlocks = 6;    // ~48 ms -- only while _reacquiring is true
    static constexpr float kSquelchRatio = 4.0f;         // signal must clear this many x the noise floor
    static constexpr float kPeakToMedian = 10.0f;        // ...and this many x the median bin of the same block (noise alone rarely passes 5 x)
    static constexpr float kSquelchEpsilon = 500.0f;      // bootstraps detection before the floor has learned anything
    static constexpr int kCalibrationBlocks = 250;         // ~2s to let the noise floor settle before any lock decision
    static constexpr float kMinElementMs = 10.0f;           // shorter than any plausible dit even at 60 WPM

    // Auto-recovery watchdog for the fast bin's own floor/peak tracking
    // getting contaminated by a strong signal on a nearby frequency
    // (not this bin's own tone) -- see the big comment in
    // processFastBlock(). ~300ms of the locked frequency looking
    // decisively strong on the *slow* bank (an independent, unaffected
    // measurement) while the fast bin's own debounced state stays stuck
    // "off" is treated as contamination, not silence, and triggers a
    // reset of this bin's own floor/peak tracking.
    // Mark/space squelch: a mark is only decoded if its mean power is this many times the locked tone's level between
    // marks. Noise at the locked tone (a room's hum or rumble, a fading carrier) swells and fades: its swells crossed
    // the threshold and were decoded as E, T and I (on the Tab5 and a phone, in a quiet room). Such a swell is only
    // about 3-6 x the level around it; a readable Morse tone is far above the noise between its elements. Found and
    // set with synthetic room noise and Morse in HamPropCore testing (see docs/PROJECT_LOG.md).
    // It must also be a lone tone in two thirds of its slow blocks: the locked bin (or a neighbour) kPeakToMedian x the
    // block's median bin (a click or a burst of noise lifts every bin together), kPeakToSide x the quieter side's
    // bins 3-4 away, and no other peak within 10 dB anywhere. A voice is a ladder of harmonics, each a narrow tone
    // switched by its syllables: its marks passed when one block in a mark was enough (talking near the phone
    // decoded as E, T, A...); with two thirds, a synthetic voice decodes nothing in 30 s and Morse is unchanged.
    // Until the level between marks is known (kSpaceLearnBlocks "off" blocks, ~50 ms after a lock or retune) no mark
    // is decoded: the first marks after a lock on noise were otherwise always let through.
    // Known limit (as before these): two stations of equal strength ~200 Hz apart - the 250 Hz fast bin hears both.
    static constexpr float kMinMarkContrast = 10.0f;
    static constexpr float kPeakToSide = 6.0f;    // ...and this many x the quieter side's bins 3-4 away (a voice's next harmonic)
    static constexpr int kSpaceLearnBlocks = 12;
    static constexpr int kStuckRecoveryBlocks = 75;  // ~300ms at kFastBlockMs

    // Debounce window (how many consecutive fast blocks an on/off flip
    // must sustain before being accepted) now scales with the current
    // dit length rather than a fixed block count, adopted from a 1981
    // hardware CW decoder's software recreation (see docs/PROJECT_LOG.md,
    // "morse1981"): a fixed absolute debounce time is proportionally
    // huge at slow WPM (over-tolerant of real noise) and proportionally
    // tiny at fast WPM (too willing to accept a flicker as real). Scaling
    // it to a fraction of the current dit keeps the same relative
    // tolerance at any speed. kFastBlockMs is FastGoertzelBin::kWindow
    // samples' duration at this sample rate (64 samples @ 16kHz = 4ms).
    static constexpr float kFastBlockMs     = 1000.0f * FastGoertzelBin::kWindow / kSampleRateHz;  // duration of one fast block, in ms
    static constexpr float kDebounceFrac    = 0.30f;   // fraction of one dit-unit the debounce window spans
    static constexpr int kDebounceBlocksMin = 1;        // never require fewer than one confirming block
    static constexpr int kDebounceBlocksMax = 6;        // cap, so a very slow/large dit doesn't make debounce sluggish

    // Gap-vs-dit-length multipliers that decide element/letter/word
    // boundaries. Standard Morse timing is 1 unit intra-character gap,
    // 3 units inter-letter, 7 units inter-word; 1.7 is close to the
    // geometric mean of 1 and 3 (~1.73), roughly equidistant from both in
    // ratio terms.
    //
    // This was lowered to 1.3 in 0.4.9 because a capture at the time
    // showed real letter gaps measuring under the 1.7 threshold and
    // fusing letters together -- but that capture predates the dit
    // drift fixes (0.4.10/0.4.12), so its dit estimate (and therefore
    // every gap-vs-dit comparison in it) can't be trusted as clean
    // evidence. With dit now tracking accurately and staying stable
    // (confirmed: 46-53ms across multiple 0.4.12/0.4.13 captures, right at
    // the true ~47ms), a precise alignment pass on real 0.4.13 data showed
    // the *opposite* problem instead: short, single/repeated-element
    // letters (T, S, O) survived reliably while longer mixed-element
    // letters (H, A, N, M, R, K, G) kept vanishing -- consistent with the
    // true ~51ms intra-element gap occasionally running long enough under
    // reverb to cross a 1.3x threshold (~61-69ms at this dit) and
    // splitting a multi-element letter early, which a single-element
    // letter like T has no internal gap to be vulnerable to at all.
    // Reverted to 1.7 now that the dit-instability confound is gone, to
    // restore the margin above the true intra-element gap.
    static constexpr float kLetterGapUnits = 1.7f;  // gap-to-dit ratio above which a gap ends the current letter
    static constexpr float kWordGapUnits   = 4.5f;  // gap-to-dit ratio above which a gap also ends the current word

    // Fraction of (peak - floor) span the fast envelope must cross to be
    // accepted as "on"/"off" -- a Schmitt trigger, so onFrac > offFrac
    // gives hysteresis against noise flicker while a mark is nominally
    // steady. offFrac used to sit at 0.20, just barely above the floor,
    // which meant a mark's measured "on" duration lasted until the power
    // decayed almost all the way back down -- fine for a clean electrical
    // signal, but a real room's reverberant tail of the same tone decays
    // *through* the 0.20-0.35 band for tens of ms after the actual keying
    // stopped, so that decay tail got counted as still-keyed time,
    // stretching marks and eating into the following gap. Narrowing the
    // band by raising offFrac makes the decoder call "off" as soon as the
    // direct-path tone ends and only the decaying echo remains, treating
    // that echo as what it is rather than as more keying. (Confirmed by a
    // real capture through the Tab5's own mic: the envelope sat in a
    // 90-250 mid-band range in gaps that should have read near the floor,
    // for well over 100ms -- see docs/PROJECT_LOG.md.)
    //
    // Now runtime-adjustable (0.5.0, "morse1981" full-UI-replica request
    // -- see docs/PROJECT_LOG.md) rather than fixed constants, via a
    // sensitivity slider mapped with the same formula the reference
    // uses (`frac = 0.80 - (sensitivity-0.15)*0.67`). The default
    // sensitivity (kDefaultSensitivity01, chosen to reproduce the
    // 0.35/0.30 values above rather than the reference's own 60%
    // default) preserves this project's own hard-won tuning unless the
    // user actually moves the slider. kDefaultSensitivity01 itself is
    // declared public, above, alongside the runtime controls that use it.
    float _onThreshFrac  = 0.35f;  // "off"->"on" crossing point, as a fraction of the floor-to-peak span -- set from sensitivity in begin()
    float _offThreshFrac = 0.30f;  // "on"->"off" crossing point, as a fraction of the floor-to-peak span -- kept a fixed gap below onFrac
    float _sensitivity01  = kDefaultSensitivity01;  // current slider position, 0..1, exposed via setSensitivity01()/sensitivity01()

    // Sideband reference frequencies for the fast on/off decision,
    // adopted from "morse1981" (see docs/PROJECT_LOG.md): rather than
    // relying solely on a time-history noise floor (which, being an EMA,
    // necessarily lags a sudden real-time noise change), also measure
    // power at +-280Hz away from the locked tone using two more fast
    // Goertzel bins, updated every fast block right alongside the main
    // one. A burst of broadband noise/interference shows up at the
    // sidebands *at the same instant* it shows up near the tone, so this
    // reference reacts immediately rather than only after the slow floor
    // EMA catches up.
    static constexpr float kSidebandOffsetHz = 280.0f;  // frequency offset (matching morse1981's own choice) for each sideband bin

    DcBlocker _dcBlock;              // removes ADC DC bias before any Goertzel bin sees the samples
    GoertzelBin _bins[kNumBins];     // slow, narrowband bank used to find/track which frequency carries the tone
    float _globalNoiseFloor = 0.0f;  // slow-bank noise floor, learned from whichever bin is quietest each block
    int _sampleCounter      = 0;     // counts samples toward the next slow-bank analysis block

    int _lockedBin      = 0;      // index into _bins[] currently believed to carry the tone
    int _pendingBin     = 0;      // index of a candidate rival bin outcompeting the locked one
    int _candidateStreak = 0;     // consecutive blocks the rival bin has led by
    int _nbBin = 0, _nbStreak = 0; // a neighbour of the locked bin and the consecutive blocks it has led (refines the lock)
    bool _everLocked     = false; // whether a real lock has ever been achieved since begin()
    bool _reacquiring    = false; // true only between autoTune() and the next completed lock -- see kReacquireSwitchBlocks
    int _calibrationBlocksLeft = kCalibrationBlocks;  // countdown before any lock decision is even considered

    // Fast, single-window resonator retuned to _lockedBin's frequency
    // whenever the lock changes, used for the on/off envelope decision
    // instead of the (much smoother) slow bank -- see goertzel.h.
    FastGoertzelBin _fastBin;    // fast bin tuned to the locked frequency itself
    FastGoertzelBin _fastBinLo;  // fast bin tuned kSidebandOffsetHz below the locked frequency (sideband reference)
    FastGoertzelBin _fastBinHi;  // fast bin tuned kSidebandOffsetHz above the locked frequency (sideband reference)
    int _fastSampleCounter = 0;  // counts samples toward the next fast-bin analysis block
    float _fastPowerSmoothed = 0.0f;  // light EMA-smoothed version of _fastBin.power(), used for every on/off/floor/peak decision below instead of the raw reading -- see the big comment in processFastBlock()
    float _fastNoiseFloor  = 0.0f;  // own scale, decoupled from _globalNoiseFloor
    float _fastPeak        = 1.0f;  // fast bin's own tracked peak, floored against _fastNoiseFloor
    float _sidebandRef      = 0.0f;  // smoothed same-instant reference level from the two sideband bins
    float _spaceLevel        = 0.0f;   // the locked tone's mean power between marks (settled "off" fast blocks) - the noise a mark must beat
    int _spaceBlocks         = 0;      // "off" blocks learned into it since the last retune (a running mean until kSpaceLearnBlocks)
    double _markSum          = 0.0;    // the current mark's fast-block powers, summed
    int _markN               = 0;      // ... and how many
    float _markLevel         = 0.0f;   // accepted marks' mean power (EMA) - with _spaceLevel, the signal-to-noise shown
    int _markBlocks          = 0;      // slow blocks seen during the current mark
    int _markLone            = 0;      // ... and how many of them showed the locked tone alone (see processBlock)
    float _pendingGapMs      = -1.0f;  // the gap before the current mark, kept until the mark is accepted (-1: none to record)
    int _stuckBlocks         = 0;      // consecutive fast blocks the locked frequency has looked stuck despite clear signal -- see kStuckRecoveryBlocks in processFastBlock()

    bool _toneOn          = false;  // current debounced on/off state
    int _debounceCounter  = 0;      // consecutive fast blocks the pending state has been seen
    bool _pendingState    = false;  // on/off state waiting for debounce confirmation

    float _level01      = 0.0f;               // 0..1 normalized envelope level (floor..peak), for the UI meter
    float _lastPower[kNumBins] = {};           // most recent power reading per slow bin, for diagnostics
    float _lastOnThresh  = 0.0f;               // most recently computed "on" threshold, for diagnostics
    float _lastOffThresh = 0.0f;               // most recently computed "off" threshold, for diagnostics

    float _ditMs           = 60.0f;  // seeded at ~20 WPM; authoritative value is now set by fitTimingFromHistory()
    float _bias             = 0.0f;   // envelope-decay/detector-lag compensation, in ms (see fitTimingFromHistory())
    int64_t _samplesFed    = 0;      // audio samples processed since begin(): the clock (16 per ms)
    int64_t clockUs() const { return _samplesFed * 125 / 2; } // microseconds of audio processed (1e6 / 16000 = 62.5 us a sample)
    int64_t _markStartUs   = 0;      // timestamp the current/most recent mark began
    int64_t _markEndUs     = 0;      // timestamp the current/most recent mark ended
    bool _haveMarkEnd      = false;  // whether _markEndUs holds a meaningful value yet
    bool _letterFlushed    = true;   // whether the current letter gap has already been acted on
    bool _wordSpaceFlushed = true;   // whether the current word gap has already been acted on

    // Rolling history of recent element durations (marks and gaps, both
    // kinds mixed together in arrival order), adopted from "morse1981"
    // (see docs/PROJECT_LOG.md): a global least-squares fit over this
    // whole window, run in fitTimingFromHistory(), replaces the old
    // purely-reactive per-element EMA as the authority for _ditMs (and
    // introduces _bias alongside it). A single ambiguous or noise-
    // corrupted element barely moves a fit over ~70 data points, which
    // is naturally resistant to the exact kind of one-direction
    // compounding drift that took three separate patches (0.4.8, 0.4.10,
    // 0.4.12) to defend the old EMA against.
    struct HistEntry {
        float ms;      // measured duration, in ms (uncorrected -- the fit itself solves for the bias term)
        bool isMark;    // true if this entry is a mark (tone-on) duration, false if a gap (tone-off) duration
    };
    static constexpr int kHistoryCap = 72;  // matches morse1981's own window (36 marks + 36 gaps)
    HistEntry _history[kHistoryCap];         // ring buffer of recent element durations
    int _historyLen = 0;                      // how many valid entries _history currently holds (up to kHistoryCap)
    int _historyPos  = 0;                      // ring-buffer write cursor

    std::string _currentSymbol;  // in-progress letter's dot/dash string, for live display and logging
    std::string _lastFinalizedSymbol;  // last completed letter's pattern, used by currentSymbol() under "buffered output"
    // Parallel to _currentSymbol -- one entry per element of the
    // in-progress letter, carrying how confident classifyMark() (or the
    // FUSED decomposition) actually was, rather than just the hard
    // '.'/'-' it committed to. Consumed by decodeMorseProbabilistic() at
    // letter-finalize time instead of an exact string match.
    std::vector<ElementProb> _currentElementProbs;  // per-element dit/dash confidence for the in-progress letter
    std::string _decodedText;                        // the full running transcript

    // Runtime control-panel state (0.5.0, "morse1981" full-UI-replica
    // request -- see docs/PROJECT_LOG.md and the public setters above,
    // which are this state's only intended mutators).
    static constexpr float kDefaultStartWpm = 20.0f;  // matches the old fixed 60ms dit seed (1200/20 = 60)
    float _startWpm         = kDefaultStartWpm;  // "start speed": what resetSpeed()/begin() seed the dit estimate to
    bool _followTone         = true;              // "follow the tone": keep re-locking onto the strongest bin when true
    bool _fastSpeedLock      = false;              // "fast speed lock": blend new timing fits in more aggressively when true
    bool _holdUntilLocked    = true;                // "hold output until locked": suppress decoding before the first real lock
    bool _bufferedOutput     = false;                // "buffered output": currentSymbol() only updates at letter-finalize time
    static constexpr int kReacquireCalibrationBlocks = 25;  // ~0.2s settle after autoTune() -- floor is already learned, unlike a cold boot

    void processBlock();                       // slow-bank analysis: bin selection / frequency lock
    void processFastBlock();                    // fast-bin analysis: on/off envelope decision
    void onToneRisingEdge(int64_t nowUs);        // called when the debounced state flips to "on"
    void onToneFallingEdge(int64_t nowUs);       // called when the debounced state flips to "off"
    void finalizeLetterIfAny();                  // matches the in-progress symbol against the codebook and appends it
    char classifyMark(float durationMs);         // hard-classifies one mark as dot/dash, also recording its confidence
    void pushHistory(float ms, bool isMark);     // records one element duration into the rolling history ring buffer
    void fitTimingFromHistory();                 // grid-search fit of dit length + bias over the rolling history
    void checkSyncLoss();                        // watches recent decoded text for signs of derailment and resyncs if found
};

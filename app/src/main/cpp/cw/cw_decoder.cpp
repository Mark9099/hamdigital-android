#include "cw_decoder.h"

#include <cstdlib>    // std::abs (neighbouring bins)
#include <algorithm>  // std::min/std::max, used throughout for clamping
#include <cstdio>     // printf, for the diagnostic log lines
#include <string>     // std::string::substr/find, used by checkSyncLoss()
#include <vector>     // std::vector, used by checkSyncLoss()'s allowed-word list

#ifdef CW_DEBUG
#define CW_LOG(...) printf(__VA_ARGS__)                  // diagnostic lines (as the original project)
#else
#define CW_LOG(...) ((void)0)                           // quiet by default
#endif
#include "cw_morse_table.h"  // ElementProb, decodeMorseProbabilistic()

namespace {
inline float clampf(float v, float lo, float hi) { return v < lo ? lo : (v > hi ? hi : v); }  // simple clamp helper used throughout this file
inline float gaussianDensity(float x, float mu, float sigma) {  // unnormalized Gaussian density, used for the Bayesian element classifier
    float z = (x - mu) / sigma;   // standardized distance from the mean, in units of sigma
    return expf(-0.5f * z * z) / sigma;  // Gaussian probability density function
}
}  // namespace

void CwDecoder::begin() {
    _dcBlock = DcBlocker();  // fresh DC-blocking filter state
    for (int i = 0; i < kNumBins; ++i) {           // every slow candidate-frequency bin
        float freq = 350.0f + 50.0f * i;             // this bin's assigned frequency, 350..1000Hz in 50Hz steps
        _bins[i].init(freq, kSampleRateHz);           // (re)initialize it fresh
    }
    _fastBin.init(350.0f, kSampleRateHz);              // placeholder until the first real lock retunes it
    _fastBinLo.init(350.0f - kSidebandOffsetHz, kSampleRateHz);  // sideband placeholder, likewise retuned on lock
    _fastBinHi.init(350.0f + kSidebandOffsetHz, kSampleRateHz);  // sideband placeholder, likewise retuned on lock
    _fastSampleCounter = 0;    // no samples fed toward the next fast block yet
    _fastPowerSmoothed = 0.0f;  // smoothed reading starts unlearned, same as the raw floor/peak below
    _stuckBlocks        = 0;     // watchdog counter starts fresh
    _fastNoiseFloor    = 0.0f;  // fast bin's own floor starts unlearned
    _fastPeak          = 1.0f;  // fast bin's own peak starts at a minimal placeholder
    _sidebandRef        = 0.0f;  // sideband reference starts unlearned
    _spaceLevel = 0.0f; _spaceBlocks = 0; _markLevel = 0.0f;  // the levels between and of marks likewise (relearned)
    _globalNoiseFloor = 0.0f;  // slow-bank floor starts unlearned
    _sampleCounter    = 0;      // no samples fed toward the next slow block yet
    _lockedBin = _pendingBin = 0;  // no lock yet; both point at the placeholder bin 0
    _candidateStreak         = 0;   // no rival bin has been seen yet
    _everLocked               = false;  // no real lock has ever happened yet
    _reacquiring               = false;  // not mid-reacquire; that only ever starts via autoTune()
    _calibrationBlocksLeft    = kCalibrationBlocks;  // full calibration countdown before any lock decision
    _toneOn                  = false;  // starts in the "off" state
    _debounceCounter          = 0;      // nothing pending yet
    _level01                  = 0.0f;   // meter starts at zero
    setSensitivity01(_sensitivity01);   // (re)apply the sensitivity->threshold-fraction formula from the current/default slider position
    _ditMs                    = 1200.0f / _startWpm;  // seeded from the configured start speed; fitTimingFromHistory() takes over once real elements arrive
    _bias                     = 0.0f;   // no lag compensation known yet
    _haveMarkEnd              = false;  // no mark has ever ended yet
    _letterFlushed            = true;   // nothing pending to flush
    _wordSpaceFlushed         = true;   // nothing pending to flush
    _historyLen                = 0;      // rolling timing history starts empty
    _historyPos                 = 0;      // ring-buffer cursor starts at the beginning
    _currentSymbol.clear();     // no in-progress letter yet
    _lastFinalizedSymbol.clear();  // no completed letter yet either
    _currentElementProbs.clear();  // no in-progress per-element confidences yet
    _decodedText.clear();       // transcript starts empty
}

void CwDecoder::autoTune() {
    // "Auto tune" (0.5.0, morse1981 full-UI-replica request): force a
    // fresh re-acquisition of the tone frequency, as if starting over,
    // but without repeating the full cold-boot calibration wait -- the
    // ambient noise floor (_globalNoiseFloor) is already learned and is
    // deliberately left untouched here, so only a short settle is
    // needed before the next lock decision is considered.
    _everLocked            = false;                      // no real lock until the next one actually happens
    _reacquiring            = true;                        // use the shorter kReacquireSwitchBlocks confirmation streak until the next lock completes
    _lockedBin = _pendingBin = 0;                          // back to the placeholder bin
    _candidateStreak         = 0;                           // no rival bin streak in progress
    _calibrationBlocksLeft   = kReacquireCalibrationBlocks;  // brief settle, not the full cold-boot wait
    _fastPowerSmoothed       = 0.0f;                          // smoothed reading no longer means anything on the old frequency either
    _stuckBlocks              = 0;                              // watchdog counter starts fresh
    _fastNoiseFloor          = 0.0f;                          // fast bin's floor no longer means anything on the old frequency
    _fastPeak                = 1.0f;                           // reset to a minimal placeholder
    _sidebandRef              = 0.0f;                           // likewise for the sideband reference
    _spaceLevel = 0.0f; _spaceBlocks = 0; _markLevel = 0.0f;  // the levels between and of marks likewise (relearned)
    CW_LOG("[control] auto tune -- re-acquiring\n");  // log for visibility alongside the other diagnostic lines
}

void CwDecoder::forceLock(int binIndex) {
    // "Click the waterfall to tune" (0.5.0, morse1981 full-UI-replica
    // request): the human has already identified the tone visually, so
    // lock onto it immediately rather than waiting for the normal
    // streak-based switch to confirm it independently.
    if (binIndex < 0 || binIndex >= kNumBins) return;  // ignore an out-of-range tap rather than corrupt state

    _lockedBin       = binIndex;  // adopt the tapped bin immediately
    _pendingBin      = binIndex;  // no rival candidate pending
    _candidateStreak = 0;          // nothing to confirm; this lock is already decided
    _everLocked       = true;       // a real lock now exists
    // Retune the fast envelope-detection bin (and its two sideband
    // reference bins), the same reset the automatic switch path uses in
    // processBlock() -- their history is on whatever frequency was
    // locked before and no longer means anything.
    _fastBin.init(_bins[_lockedBin].frequency(), kSampleRateHz);  // retune the on-frequency fast bin
    _fastBinLo.init(std::max(_bins[_lockedBin].frequency() - kSidebandOffsetHz, 50.0f), kSampleRateHz);  // retune the lower sideband bin
    _fastBinHi.init(_bins[_lockedBin].frequency() + kSidebandOffsetHz, kSampleRateHz);  // retune the upper sideband bin
    _fastSampleCounter = 0;                       // restart the fast-block sample count
    _fastPowerSmoothed = 0.0f;                     // smoothed reading no longer means anything on the new frequency
    _stuckBlocks        = 0;                        // watchdog counter starts fresh
    _fastNoiseFloor    = 0.0f;                     // fast bin's floor no longer means anything on the new frequency
    _fastPeak          = std::max(_globalNoiseFloor * 1.5f, 1.0f);  // seed a modest starting peak
    _sidebandRef        = 0.0f;                     // sideband reference no longer means anything on the new frequencies
    _spaceLevel = 0.0f; _spaceBlocks = 0; _markLevel = 0.0f;  // the levels between and of marks likewise (relearned)
    CW_LOG("[control] forced lock to bin %d (%.0fHz)\n", binIndex, _bins[binIndex].frequency());  // log for visibility
}

void CwDecoder::resetSpeed() {
    // "Reset speed" (0.5.0, morse1981 full-UI-replica request): give up
    // on the current dit/bias estimate and rolling history, the same
    // manual-override action as the reference's own button of the same
    // name.
    _ditMs      = 1200.0f / _startWpm;  // back to the configured start speed
    _bias       = 0.0f;                  // no lag compensation known yet
    _historyLen = 0;                      // discard the rolling timing history
    _historyPos = 0;                       // restart the ring-buffer write cursor
    CW_LOG("[control] reset speed to %.0f WPM (%.1fms dit)\n", _startWpm, _ditMs);  // log for visibility
}

void CwDecoder::setSensitivity01(float s) {
    // "Sensitivity" slider (0.5.0, morse1981 full-UI-replica request):
    // remaps 0..1 to the on/off threshold fractions via the same formula
    // the reference itself uses, so the two projects' sliders behave
    // comparably (its own default of 60% corresponds to roughly 0.50
    // here; this project's own tuned default of 0.35 corresponds to
    // roughly 82%, kDefaultSensitivity01, so the slider starts wherever
    // reproduces the existing validated behavior rather than at the
    // reference's own default position).
    _sensitivity01 = clampf(s, 0.0f, 1.0f);                                  // clamp to a valid slider position
    _onThreshFrac  = clampf(0.80f - (_sensitivity01 - 0.15f) * 0.67f, 0.10f, 0.90f);  // morse1981's own mapping formula
    _offThreshFrac = clampf(_onThreshFrac - 0.05f, 0.05f, _onThreshFrac);              // keep a fixed hysteresis gap below onFrac
}

void CwDecoder::setStartWpm(float wpm) {
    // "Start speed" (0.5.0, morse1981 full-UI-replica request): the WPM
    // resetSpeed() (and a future begin()) seed the dit estimate to.
    // Clamped to the same absolute range fitTimingFromHistory() itself
    // searches (see kDitFloorMs/kDitCeilMs-equivalent bounds there).
    _startWpm = clampf(wpm, 1200.0f / 260.0f, 1200.0f / 24.0f);  // matches fitTimingFromHistory()'s own 24-260ms dit bounds
}

float CwDecoder::signalSnrDb() const {
    // The marks' mean power against the level between them (the mark/space squelch's own figure) - what a listener
    // means by "how far above the noise". The peak-to-floor ratio this used before measured the loudest instant
    // against a floor learned low, and read 75-90 dB for room noise.
    if (_markLevel > 0.0f && _spaceLevel > 0.0f) return 10.0f * log10f(_markLevel / _spaceLevel); // power ratio, dB
    return 0.0f;                                     // nothing decoded yet
}

float CwDecoder::peakToFloorDb() const {
    // Rough SNR proxy for the "Signal strength (dB)" status meter (0.5.0,
    // morse1981 full-UI-replica request): the fast bin's own tracked
    // peak against its own tracked floor, in dB. Not a calibrated
    // absolute measurement -- power, not amplitude, so halved relative
    // to a true amplitude-domain dB figure -- but consistent and useful
    // as a relative indicator, which is all a live status meter needs.
    float ratio = std::max(_fastPeak, 1.0f) / std::max(_fastNoiseFloor, 1.0f);  // peak-to-floor power ratio, floored to avoid div-by-zero
    return 10.0f * log10f(ratio);  // power-domain dB (10*log10, not 20*log10 -- this ratio is already power, not amplitude)
}

void CwDecoder::processAudioBlock(const int16_t* samples, size_t count) {
    for (size_t i = 0; i < count; ++i) {          // every raw sample in this block
        ++_samplesFed;                                   // the clock: one sample = 62.5 us of audio
        float s = _dcBlock.process((float)samples[i]);  // DC-blocked sample, fed to every Goertzel bin below
        for (int b = 0; b < kNumBins; ++b) _bins[b].feed(s);  // update every slow candidate-frequency bin
        _fastBin.feed(s);     // update the fast bin at the locked tone frequency
        _fastBinLo.feed(s);   // update the lower sideband reference bin
        _fastBinHi.feed(s);   // update the upper sideband reference bin
        if (++_sampleCounter >= kBlockSize) {  // enough samples for the next slow-bank analysis block
            _sampleCounter = 0;                  // restart the count toward the block after this one
            processBlock();                       // run bin selection / frequency lock on this block
        }
        if (++_fastSampleCounter >= FastGoertzelBin::kWindow) {  // enough samples for the next fast-bin analysis block
            _fastSampleCounter = 0;                                // restart the count toward the block after this one
            processFastBlock();                                     // run the on/off envelope decision on this block
        }
    }
}

void CwDecoder::processBlock() {
    float power[kNumBins];                              // this block's power reading for every slow bin
    for (int b = 0; b < kNumBins; ++b) power[b] = _bins[b].power();  // pull each bin's freshly completed reading
    for (int b = 0; b < kNumBins; ++b) _lastPower[b] = power[b];      // remember them all for the diagnostics view

    int best     = 0;          // index of the strongest bin found so far
    float bestP  = power[0];   // its power reading
    float worstP = power[0];   // weakest power reading found so far (used as the noise-floor sample)
    for (int b = 1; b < kNumBins; ++b) {  // scan the remaining bins
        if (power[b] > bestP) {   // this bin is stronger than the best found so far
            bestP = power[b];       // remember its power
            best  = b;               // remember its index
        }
        if (power[b] < worstP) {  // this bin is weaker than the weakest found so far
            worstP = power[b];      // remember its power as the new weakest
        }
    }

    // A single, global noise floor -- learned from whichever bin is
    // quietest this block -- rather than one per bin. Per-bin floors are
    // circular: a bin only learns "quiet" from periods it isn't the tone,
    // but nothing ever tells it which periods those are without already
    // knowing which bin is the tone. The quietest bin, by construction,
    // is never the one currently carrying the tone, so this has no such
    // chicken-and-egg problem and stays valid regardless of which bin
    // ends up locked.
    _globalNoiseFloor = _globalNoiseFloor * 0.98f + worstP * 0.02f;  // slow EMA toward this block's quietest-bin reading

    // The noise floor starts at 0 and needs time to converge to a
    // realistic ambient baseline; until then, the squelch below can't
    // tell real noise from silence, and ordinary background noise right
    // after boot can look like "signal present" and win a false lock.
    // Give it a couple of seconds to settle before any lock decision is
    // even considered.
    float sorted[kNumBins]; for (int b = 0; b < kNumBins; ++b) sorted[b] = power[b]; // this block's powers
    std::nth_element(sorted, sorted + kNumBins / 2, sorted + kNumBins); // the median
    float medianP = sorted[kNumBins / 2];             // the typical bin this block (see signalPresent below)
    if (_everLocked && (_toneOn || _debounceCounter > 0)) { // during a mark (or one starting): is it a narrow tone?
        float lp = power[_lockedBin];                 // the locked bin ...
        if (_lockedBin > 0) lp = std::max(lp, power[_lockedBin - 1]);            // ... or a neighbour (a tone between bins)
        if (_lockedBin < kNumBins - 1) lp = std::max(lp, power[_lockedBin + 1]);
        // ...and clear of the bins 150-200 Hz away (3-4 bins) on at least one side (the quieter: a station on one side
        // does not spoil it). A voice is a ladder of harmonics 100-220 Hz apart, each one a narrow tone, switched on and
        // off by its syllables - it passed the median test (and on the phone, talking decoded as E, T, A...). Beside a
        // Morse tone there is only noise there; beside a voice harmonic, the next harmonic.
        float side = -1.0f;                           // the quieter side's loudest bin at 3-4 bins (none yet)
        for (int dir = -1; dir <= 1; dir += 2) {      // below, above
            float s = -1.0f;                          // this side's loudest of the two
            for (int d = 3; d <= 4; ++d) { int b = _lockedBin + dir * d; if (b >= 0 && b < kNumBins) s = std::max(s, power[b]); }
            if (s >= 0.0f && (side < 0.0f || s < side)) side = s; // the quieter side (a side off the end does not count)
        }
        int peaks = 0;                                // other peaks within 10 dB of the tone (a voice's other harmonics)
        for (int b = 0; b < kNumBins; ++b)            // (away from the tone and its neighbours; a local maximum)
            if (std::abs(b - _lockedBin) > 1 && power[b] > lp * 0.1f && (b == 0 || power[b] >= power[b - 1]) && (b == kNumBins - 1 || power[b] >= power[b + 1])) ++peaks;
#ifdef CW_STATS
        printf("[blk] on=%d med=%.1f side=%.1f peaks=%d\n", (int)_toneOn, lp / std::max(medianP, 1.0f), lp / std::max(side, 1.0f), peaks); // tuning (tools/cw_suite.sh)
#endif
        ++_markBlocks;                                // one more block of this mark
        if (lp > medianP * kPeakToMedian && lp > side * kPeakToSide && peaks == 0) ++_markLone; // the tone alone: not a click, a burst or a voice
    }

    if (_calibrationBlocksLeft > 0) {  // still within the post-boot calibration window
        --_calibrationBlocksLeft;        // one block closer to calibration finishing
        return;                          // no lock decisions during calibration
    }

    // A real tone is only ever considered for bin selection once its raw
    // power clears the noise floor by a wide, decisive margin -- CW tone
    // power at its own bin runs roughly an order of magnitude above
    // ambient in practice. Outside of that, leave the current lock and
    // candidate streak untouched (rather than resetting it) so progress
    // made during one keyed element survives the natural gap before the
    // next one -- letting the streak span a whole letter, not just one
    // dit, which finishes well inside the 25-block requirement even at
    // fairly high WPM.
    // ...and it must also stand well clear of this block's typical bin (the median of the 14). The floor above is
    // learned from the *quietest* bin, and in noise alone the loudest of 14 bins is often 4-8 x the quietest, so in
    // the gaps between elements a noise block could pass and crown a random bin as the rival, resetting the streak -
    // at 25 WPM and 10 dB no lock was ever made (found in HamPropCore testing). A real tone is hundreds of times the
    // median; noise rarely reaches 5 x.
    bool signalPresent = bestP > _globalNoiseFloor * kSquelchRatio + kSquelchEpsilon  // decisive-margin squelch test
                         && bestP > medianP * kPeakToMedian;                          // and clear of the block's typical bin
    // "Follow the tone" (0.5.0, morse1981 full-UI-replica request): once
    // a real lock exists, a disabled follow-tone means staying on that
    // bin regardless of what any other bin does -- skip rival-tracking
    // entirely rather than let a stronger bin ever win a switch. This
    // doesn't block the *first* acquisition (_everLocked is still false
    // at that point), only re-locking away from an existing one.
    if (signalPresent && !(_everLocked && !_followTone)) {  // strong enough to be worth considering, and re-locking isn't disabled
        // A tone between two 50 Hz bins (e.g. 625 Hz) splits its power between them, and which one reads stronger in a
        // given 8 ms block is down to noise. Counting only exact repeats of the same bin then resets the rival streak
        // every time the two swap, so such a tone was never locked at all (found in HamPropCore testing with synthetic
        // CW). So a neighbouring bin counts as the same tone: next to the locked bin it is no rival, and next to the
        // pending rival it continues the streak (the stronger of the pair is the one adopted).
        // A neighbour of the locked bin that leads consistently (as long as any rival must) means the tone is really
        // there - e.g. the first lock was made in a block where the neighbour happened to read stronger - so the lock
        // is refined to it. A tone midway between two bins never leads consistently, so it does not flip back and forth.
        bool nbRefine = false;                   // move the lock to the neighbour this block?
        if (_everLocked && std::abs(best - _lockedBin) == 1) { // a neighbour of the locked bin leads
            if (best == _nbBin) ++_nbStreak; else { _nbBin = best; _nbStreak = 1; } // its own streak
            nbRefine = _nbStreak >= kBinSwitchBlocks; // led for long enough
        } else if (best == _lockedBin) _nbStreak = 0; // the locked bin itself leads: the neighbour's streak ends
        if (!nbRefine && (best == _lockedBin || (_everLocked && std::abs(best - _lockedBin) == 1))) { // the locked tone (or its neighbour)
            _candidateStreak = 0;                // nothing rivaling it; clear any rival streak
        } else {                                // some other bin is currently strongest
            if (nbRefine) { _pendingBin = best; _candidateStreak = kBinSwitchBlocks - 1; _nbStreak = 0; } // adopt the neighbour below
            if (best == _pendingBin || (_candidateStreak > 0 && std::abs(best - _pendingBin) == 1)) { // the same rival as last time, or its neighbour
                if (best != _pendingBin && power[best] > power[_pendingBin]) _pendingBin = best; // follow the stronger of the pair
                ++_candidateStreak;                   // one more consecutive block in its favor
                best = _pendingBin;                   // the rival being counted
            } else {                                // a different rival than before
                _pendingBin      = best;               // start tracking this new rival
                _candidateStreak = 1;                   // its streak begins at one
            }
            int switchBlocksNeeded = _reacquiring ? kReacquireSwitchBlocks : kBinSwitchBlocks;  // shorter streak right after an explicit autoTune() request -- see kReacquireSwitchBlocks
            if (_candidateStreak >= switchBlocksNeeded) {  // the rival has led for long enough to switch
                for (int nb = _pendingBin - 1; nb <= _pendingBin + 1; nb += 2) // the stronger of the rival and its neighbours (a tone between bins)
                    if (nb >= 0 && nb < kNumBins && power[nb] > power[best]) best = nb;
                _lockedBin       = best;                   // adopt it as the new locked bin
                _candidateStreak = 0;                       // reset the streak counter
                _everLocked      = true;                     // a real lock has now happened at least once
                _reacquiring     = false;                     // reacquisition is complete; back to the normal, more cautious streak for any future switch
                // Retune the fast envelope-detection bin (and its two
                // sideband reference bins) to the newly locked
                // frequency, and reset their noise floor/peak/reference
                // -- their history is on the old frequency and no
                // longer means anything.
                _fastBin.init(_bins[_lockedBin].frequency(), kSampleRateHz);  // retune the on-frequency fast bin
                _fastBinLo.init(std::max(_bins[_lockedBin].frequency() - kSidebandOffsetHz, 50.0f), kSampleRateHz);  // retune the lower sideband bin (kept well above 0Hz)
                _fastBinHi.init(_bins[_lockedBin].frequency() + kSidebandOffsetHz, kSampleRateHz);  // retune the upper sideband bin
                _fastSampleCounter = 0;                       // restart the fast-block sample count
                _fastPowerSmoothed = 0.0f;                     // smoothed reading no longer means anything on the new frequency
                _stuckBlocks        = 0;                        // watchdog counter starts fresh
                _fastNoiseFloor    = 0.0f;                     // fast bin's floor no longer means anything on the new frequency
                _fastPeak          = std::max(_globalNoiseFloor * 1.5f, 1.0f);  // seed a modest starting peak
                _sidebandRef        = 0.0f;                     // sideband reference no longer means anything on the new frequencies
                _spaceLevel = 0.0f; _spaceBlocks = 0; _markLevel = 0.0f;  // the levels between and of marks likewise (relearned)
            }
        }
    }
}

void CwDecoder::processFastBlock() {
    // Before the first real lock, there's no known tone frequency yet --
    // the fast bin is still tuned to its startup placeholder, so its
    // power means nothing. Running the on/off envelope logic on it
    // anyway produced spurious decoded letters from pure noise during
    // the brief startup window before a real tone was ever found.
    if (!_everLocked) return;  // nothing meaningful to do until a real frequency lock has happened

    float pRaw = _fastBin.power();  // this fast block's raw, unsmoothed power reading at the locked tone frequency

    // Light envelope smoothing, applied before any threshold/floor/peak
    // decision below. FastGoertzelBin::power() is a raw per-~4ms-window
    // reading with no smoothing of its own -- fine in principle, since
    // the debounce logic further down requires several *consecutive*
    // readings to agree before accepting a state flip. But that's an
    // all-or-nothing *persistence* check on each raw reading, not a
    // filter on the signal itself: a raw reading that jitters back and
    // forth across the threshold resets that persistence count every
    // time it disagrees, and can fail to ever accumulate enough
    // agreement to flip at all -- even during a real, strong, sustained
    // tone. This was found directly from a user report of a clearly
    // visible signal on the trace panel that still didn't decode: the
    // trace's own display smoothing (kTraceSmoothAlpha, main.cpp) was
    // added because the *raw* signal looked jittery on screen, but that
    // smoothing only ever touched the display copy, never this decision
    // path -- so a signal that now looks clean and obvious after
    // display smoothing can still be exactly the kind of jittery raw
    // signal this path was failing to ever commit to. Smoothing the
    // actual decision input directly, rather than relying on persistence
    // alone to reject the jitter, fixes this at the source. "p" is used
    // below for the threshold *decision* (instTone) and the UI level
    // meter only -- floor/peak learning further down deliberately keep
    // reading the raw "pRaw" instead (see the big comment there).
    constexpr float kFastPowerSmoothAlpha = 0.4f;  // damps raw per-block jitter while still resolving a real element within 2-3 fast blocks (8-12ms), well under even a fast dit
    _fastPowerSmoothed = _fastPowerSmoothed * (1.0f - kFastPowerSmoothAlpha) + pRaw * kFastPowerSmoothAlpha;
    float p = _fastPowerSmoothed;  // the smoothed reading -- used below for the threshold *decision* (instTone) and the UI level meter only

    // This block's on/off *decision* is made first, from floor/peak/
    // sideband values exactly as the previous block left them (not yet
    // updated for this one) -- the natural causal/online order, and
    // critically what lets floor/sideband learning further down gate on
    // `instTone` (this block's own immediate, undebounced reading)
    // instead of `_toneOn` (the *debounced*, confirmed state).
    //
    // That distinction turned out to be the actual persistent root cause
    // behind two earlier attempts at this same "on-threshold drifts to
    // the top of the trace panel, decode then stops" symptom (both real
    // fixes for real secondary contamination paths, but neither one was
    // it): the debounce logic below requires `debounceBlocksNeeded` (1-6,
    // ~4-24ms) *consecutive* blocks of agreement before `_toneOn` actually
    // flips -- but the raw signal itself jumps close to its true power
    // within a single ~4ms block at a real tone's onset. That means on
    // *every single rising edge*, there are up to 6 blocks where the tone
    // is already fully on but `_toneOn` is still false by construction,
    // and floor/sideband learning gated on `_toneOn` (as both did until
    // now) fed every one of those blocks' full-power readings into the
    // floor -- not a rare one-off case, but a routine occurrence on every
    // element, compounding over a whole session regardless of which
    // signal (raw or smoothed) fed it. Gating on `instTone` instead closes
    // this for good: it flips the instant the raw signal crosses the
    // threshold, so floor/sideband learning stops the moment a rising
    // edge is even provisionally recognized, not `debounceBlocksNeeded`
    // blocks later.
    float floorNowPrev = std::max(_fastNoiseFloor, _sidebandRef);  // conservative (higher) combined floor estimate, from the previous block
    float spanPrev     = std::max(_fastPeak - floorNowPrev, 1.0f);  // floor-to-peak span, likewise from the previous block
    _level01            = clampf((p - floorNowPrev) / spanPrev, 0.0f, 1.0f);  // 0..1 normalized envelope level, for the UI meter
    float onThreshPrev  = floorNowPrev + _onThreshFrac * spanPrev;    // power level that flips "off"->"on"
    float offThreshPrev = floorNowPrev + _offThreshFrac * spanPrev;   // power level that flips "on"->"off"
    _lastOnThresh        = onThreshPrev;    // remembered for diagnostics
    _lastOffThresh       = offThreshPrev;   // remembered for diagnostics
    bool instTone         = p > (_toneOn ? offThreshPrev : onThreshPrev);  // instantaneous (undebounced) on/off reading

    // Auto-recovery watchdog: found directly from a captured session
    // where a strong, genuinely different signal appeared only 50Hz
    // away from the locked tone -- close enough that it leaked into
    // this bin's own fast-bin reading and its sideband reference alike
    // (FastGoertzelBin's ~4ms window has only ~250-500Hz selectivity),
    // driving the noise floor and sideband reference up by several
    // billion within about a second and making the on-threshold
    // unreachable. Decode stayed completely stuck until the *separate*,
    // slower automatic re-lock (processBlock(), "follow the tone")
    // eventually switched onto the interfering frequency and reset
    // everything fresh -- a real gap between contamination striking and
    // the existing recovery path catching up.
    //
    // `_lastPower[_lockedBin]` is the *slow* bank's own reading for this
    // exact frequency, on the same scale `_globalNoiseFloor` already
    // uses for bin selection -- a completely independent measurement,
    // unaffected by anything happening to this fast bin's own floor/
    // peak tracking. If it's decisively above the same squelch bar bin
    // selection itself trusts, the locked frequency genuinely has
    // strong signal on it *right now* -- so `instTone` staying stuck
    // false regardless, for a sustained stretch, means this bin's own
    // floor/peak tracking has become unreachable, not that there's no
    // real signal. During a genuine gap in real Morse timing, this
    // check naturally stays quiet too: the slow bank's own reading for
    // the locked frequency drops right along with the real silence,
    // since it's measuring the same physical signal.
    if (!instTone && _lastPower[_lockedBin] > _globalNoiseFloor * kSquelchRatio + kSquelchEpsilon) {
        if (++_stuckBlocks >= kStuckRecoveryBlocks) {
            CW_LOG("[recover] %.0fHz looked unreachable despite clear signal -- resetting floor/peak\n",
                   _bins[_lockedBin].frequency());  // visibility, matching the other control-path log lines
            _fastPowerSmoothed = pRaw;                                          // seed from this instant's raw reading rather than 0, so the reset doesn't itself look like a silence period
            _fastNoiseFloor    = 0.0f;                                            // fresh floor, same reset autoTune()/forceLock() already use
            _fastPeak          = std::max(_globalNoiseFloor * 1.5f, 1.0f);          // seed a modest starting peak, matching the same sites
            _sidebandRef        = 0.0f;                                              // sideband reference likewise no longer means anything
            // (the levels between and of marks are kept: the tone is the same, and in a quiet room this recovery runs
            // every second or two - resetting them showed "0 dB" and dropped the first marks of the next transmission)
            _stuckBlocks         = 0;                                               // reset the watchdog's own counter
        }
    } else {
        _stuckBlocks = 0;  // not currently stuck (either decoding fine, or genuinely no strong signal right now)
    }

    // Same-instant sideband reference, adopted from "morse1981" (see
    // morse_decoder.h for the full rationale): averages the two
    // off-frequency fast bins' readings, lightly smoothed, so a sudden
    // broadband noise burst is visible immediately rather than only
    // once the slower time-history floor EMA below would otherwise
    // catch up to it. Gated on `!instTone` (see the big comment above)
    // rather than running unconditionally -- the 280Hz offset
    // (kSidebandOffsetHz) is close enough, relative to FastGoertzelBin's
    // own fairly wide ~4ms-window main lobe, that a strong *locked tone
    // itself* (not just broadband noise) can genuinely leak into these
    // "sideband" bins while it's on.
    if (!instTone) {
        float refPower = 0.5f * (_fastBinLo.power() + _fastBinHi.power());  // this instant's average sideband power
        _sidebandRef     = _sidebandRef * 0.97f + refPower * 0.03f;          // light smoothing (matching morse1981's own constant)
    }

    // The fast bin's own noise floor and peak, on its own power scale
    // (a shorter Goertzel window yields numerically different power
    // than the slow bank's for the same real signal) -- decoupled from
    // _globalNoiseFloor, which is scaled for the slow bins and used only
    // for bin selection.
    if (!instTone) {  // only ever learn the floor from blocks not even provisionally believed to be tone -- see the big comment above
        // A reading far above the current floor while nominally "off" is
        // far more likely a real tone that hasn't been recognized as "on"
        // yet than genuine ambient noise -- confirmed directly in a
        // capture where a transient bin-lock switch (which resets this
        // fast bin's own floor/peak to start fresh at the newly-locked
        // frequency) reset the floor to 0 right as a loud tone was
        // already sounding, and _toneOn hadn't caught up yet. Folding
        // that reading straight in blended a huge value into the floor
        // in one step, which (via the peak-floor clamp below) raised the
        // on/off thresholds even further out of the real tone's reach --
        // a runaway that permanently locked the decoder into "off" for
        // the rest of the recording, since the on threshold was chasing
        // its own contaminated floor faster than the real tone could
        // ever catch up. Cap how far a single reading can pull the floor
        // up in one step, the same kind of protection already used for
        // the dit estimate's own compounding-drift risk (0.4.10/0.4.12).
        float floorTarget = std::min(pRaw, _fastNoiseFloor * 3.0f + 10.0f);  // cap the contribution of this one reading
        _fastNoiseFloor    = _fastNoiseFloor * 0.98f + floorTarget * 0.02f;  // slow EMA toward the capped target
    }
    if (pRaw > _fastPeak) {     // this reading is a new high
        _fastPeak = pRaw;         // adopt it as the new peak immediately
    } else {                  // this reading isn't a new high
        _fastPeak *= 0.998f;    // let the peak decay gently toward recent readings over time
    }
    // Don't let peak decay so close to the floor that ordinary background
    // noise variance starts crossing the on/off thresholds below. A real
    // capture with nothing environmental changed still flooded the
    // decoded text with spurious single-dot letters after the 0.4.10 fix
    // stopped an unrelated bug from masking it -- root cause: a genuine
    // tone reads far above ambient (kSquelchRatio=4x is the bar the bin-
    // lock squelch elsewhere in this file uses for the same reason), but
    // peak decaying toward floor during any silence longer than ~1-2s
    // shrinks the floor-to-peak span, and with it the *absolute* gap
    // between floor and onThresh -- meaning the same ordinary noise burst
    // that couldn't cross it right after a loud tone increasingly can, the
    // longer the silence lasts. Peak decaying doesn't mean the next real
    // tone got quieter; it just means nothing has refreshed it. Floor the
    // span at a fixed multiple of the noise floor instead of letting it
    // collapse. 8x (0.4.11) turned out still not strict enough over
    // *minutes* of true idle silence rather than the few seconds between
    // words: an idle capture picked up scattered single-dot letters at
    // roughly one per second, well above the general few-per-minute rate
    // of a stray transient. A real tone's peak, once one has ever been
    // heard, runs orders of magnitude (thousands-plus) above the ambient
    // floor -- so raising this multiplier well past what any real
    // ambient transient should reach costs nothing in real-signal
    // sensitivity (a genuine tone will clear either bar trivially) while
    // directly raising the bar ordinary room noise has to clear.
    _fastPeak = std::max(_fastPeak, std::max(_fastNoiseFloor, 1.0f) * 25.0f);  // floor the span against a collapsed peak

    // Debounce window (see morse_decoder.h) now scales with the current
    // dit length instead of a fixed block count, adopted from
    // "morse1981": a fixed absolute debounce time is proportionally huge
    // at slow WPM and proportionally tiny at fast WPM.
    int debounceBlocksNeeded = (int)(kDebounceFrac * _ditMs / kFastBlockMs + 0.5f);  // fraction of one dit-unit, in fast-block units, rounded
    debounceBlocksNeeded     = std::max(kDebounceBlocksMin, std::min(kDebounceBlocksMax, debounceBlocksNeeded));  // clamp to sane bounds

    if (instTone != _toneOn) {                    // the instantaneous reading disagrees with the currently accepted state
        if (instTone != _pendingState) {             // this is a different candidate than whatever was pending
            _pendingState    = instTone;               // start tracking this new candidate state
            _debounceCounter = 1;                        // this is its first confirming reading
        } else {                                       // same candidate as last time
            ++_debounceCounter;                           // one more consecutive reading agreeing with it
        }
        if (_debounceCounter >= debounceBlocksNeeded) {  // enough consecutive agreement to accept the flip
            _toneOn          = instTone;                   // commit to the new state
            _debounceCounter = 0;                           // reset for the next transition
            int64_t nowUs    = clockUs();         // timestamp this accepted transition
            if (_toneOn) {                                    // the state just flipped to "on"
                onToneRisingEdge(nowUs);                         // handle the start of a new mark
            } else {                                           // the state just flipped to "off"
                onToneFallingEdge(nowUs);                        // handle the end of a mark
            }
        }
    } else {                                       // the instantaneous reading already agrees with the current state
        _debounceCounter = 0;                         // nothing pending; clear any stale debounce progress
    }
    if (_toneOn) { _markSum += pRaw; ++_markN; }    // during a mark: its power, for its mean (the squelch)
    else if (_debounceCounter == 0) {                // between marks, settled (not mid-flip): the level a mark must beat
        _markBlocks = _markLone = 0;                 // (and the next mark's lone-tone count starts afresh)
        float a = _spaceBlocks < kSpaceLearnBlocks ? 1.0f / (_spaceBlocks + 1) : 0.02f; // a running mean at first, then a slow EMA
        // A block where the next mark is starting (not yet over the threshold, so still "off") holds part of a tone
        // many times the noise; each such block pulled the level up. So, once learned, a block counts for at most
        // 3 x the level (noise alone rarely goes over it).
        float v = _spaceBlocks < kSpaceLearnBlocks ? pRaw : std::min(pRaw, _spaceLevel * 3.0f); // this block's say
        _spaceLevel += (v - _spaceLevel) * a; ++_spaceBlocks;
    }
}

void CwDecoder::onToneRisingEdge(int64_t nowUs) {
    _pendingGapMs = -1.0f;                                // no gap to record unless one is measured below
    if (_haveMarkEnd) {                                   // there is a previous mark to measure this gap from
        float gapMs = (nowUs - _markEndUs) / 1000.0f;        // elapsed silence since the previous mark ended

        // A serial capture window and a test playback are started
        // independently by a human, so there's no reliable way to know
        // where in a capture log the real audio actually begins --
        // repeatedly a source of wasted, misaligned test captures this
        // session. A gap this long (real inter-word gaps in the test
        // material are ~330ms) only happens between separate playbacks,
        // so logging it marks the start of each new one unambiguously,
        // without needing a device reset (which loses the accumulated
        // _decodedText and adds its own timing uncertainty).
        if (gapMs > 2000.0f) {                                                     // long enough to only ever happen between separate playbacks
            CW_LOG("[start] new transmission after %.1fs silence\n", gapMs / 1000.0f);  // sync marker for capture alignment
        } else if (gapMs < 1000.0f) {                                               // plausible real Morse timing, worth fitting
            _pendingGapMs = gapMs;            // recorded for the timing fit once this mark is accepted (onToneFallingEdge)
        }
        // Gaps between 1000ms and 2000ms are neither -- too long to be
        // real Morse timing, too short to be confidently a new
        // transmission -- so they're simply not recorded either way.
    }
    _markStartUs = nowUs;  // remember when this new mark began, for its own duration measurement later
    _markSum = 0.0; _markN = 0;  // its power, summed from here
}

char CwDecoder::classifyMark(float durationMs) {
    // _ditMs and _bias are now the *output* of fitTimingFromHistory() (a
    // global least-squares fit over the recent element history, adopted
    // from "morse1981" -- see docs/PROJECT_LOG.md) rather than being
    // nudged here per element the way earlier versions did. This
    // function only reads them to make an immediate classification
    // decision and record how confident it was; the fit itself runs
    // separately, after this element is pushed into the history.
    float dit = _ditMs;  // current authoritative dit-length estimate

    // A mark measures long by ~bias (envelope-decay/detector lag), so
    // subtract it back out before comparing against the ideal, unbiased
    // dit-based boundary -- the same "detector lag" concept "morse1981"
    // fits jointly with speed, replacing this project's earlier ad-hoc
    // off-threshold-fraction tuning (0.4.6/0.4.9/0.4.14) for the same
    // underlying symptom.
    float corrected = durationMs - _bias;  // bias-corrected duration, representing the true underlying element length

    // Bayesian dit/dash likelihood (CW Skimmer/Morse Expert's documented
    // technique -- see docs/PROJECT_LOG.md): approximate the observed
    // dit/dash duration distributions as Gaussians around the current dit
    // estimate and 3x that, and record how *confident* this element's
    // classification actually was rather than only the hard symbol below.
    // A duration sitting right on the boundary -- exactly what a room's
    // reverb tends to produce -- is recorded as genuinely ambiguous (e.g.
    // 55%/45%) instead of forced into full confidence before the letter is
    // even matched; finalizeLetterIfAny() weighs that uncertainty against
    // the codebook instead of losing it to a premature hard decision.
    float sigmaDit  = std::max(dit * 0.35f, 5.0f);          // spread of the dot-duration distribution
    float sigmaDash = std::max(dit * 3.0f * 0.35f, 5.0f);   // spread of the dash-duration distribution
    float pDitRaw   = gaussianDensity(corrected, dit, sigmaDit);          // likelihood this is a dot
    float pDashRaw  = gaussianDensity(corrected, dit * 3.0f, sigmaDash);  // likelihood this is a dash
    float pDit      = pDitRaw / std::max(pDitRaw + pDashRaw, 1e-9f);      // normalized posterior probability of "dot"
    _currentElementProbs.push_back({pDit, 1.0f - pDit});                  // record this element's confidence for later letter matching

    char elem;                       // the hard '.'/'-' symbol this element commits to
    if (corrected < dit * 2.0f) {      // bias-corrected duration sits closer to one dit-unit than three
        elem = '.';                     // classify as a dot
    } else {                          // sits closer to three dit-units than one
        elem = '-';                     // classify as a dash
    }
    return elem;  // _ditMs/_bias themselves are updated separately, by fitTimingFromHistory()
}

void CwDecoder::onToneFallingEdge(int64_t nowUs) {
#ifdef CW_STATS
    printf("[end]\n");
#endif
    float durationMs = (nowUs - _markStartUs) / 1000.0f;  // measured duration of the mark that just ended

    if (durationMs < kMinElementMs) {
        // Implausibly short even for a very fast dit (60 WPM is 20ms;
        // this is under half that) -- almost certainly an artifact, e.g.
        // a boundary click where the source concatenates separate
        // per-letter audio clips, not a real element. Ignore it
        // entirely: don't touch the symbol, the dit estimate, or
        // _markEndUs, so it doesn't disturb the real silence
        // measurement already in progress from the previous mark.
        CW_LOG("[elem] IGNORED tiny blip dur=%.1fms\n", durationMs);  // log it for visibility, but otherwise discard
        return;                                                        // leave every piece of state untouched
    }

    // The mark/space squelch (kMinMarkContrast): noise swelling at the locked tone is dropped like a blip - nothing
    // decoded, no timing learned from it, and the next gap is measured from the last real mark.
    float markMean = _markN > 0 ? (float)(_markSum / _markN) : 0.0f; // this mark's mean power
    float contrast = markMean / std::max(_spaceLevel, 1.0f);          // ... against the level between marks
    bool lone = _markBlocks > 0 && _markLone * 3 >= _markBlocks * 2; // the tone alone in two thirds of its blocks (a half let 3 voice letters in 30 s through)
    CW_LOG("[elem] contrast %.1f (mark %.0f space %.0f) lone %d/%d dur=%.1fms\n", contrast, markMean, _spaceLevel, _markLone, _markBlocks, durationMs);
    if (_spaceBlocks < kSpaceLearnBlocks || contrast < kMinMarkContrast || !lone) { // level unknown, not clear of it, or not a lone tone
        CW_LOG("[elem] IGNORED mark (noise)\n");                     // log it for visibility
        return;                                                        // as a blip: state untouched
    }
    _markLevel = _markLevel > 0.0f ? _markLevel * 0.8f + markMean * 0.2f : markMean; // the marks' level (signal-to-noise)
    if (_pendingGapMs >= 0.0f) {                           // the gap before it, held until now
        pushHistory(_pendingGapMs, false);                  // record it for the rolling timing fit
        fitTimingFromHistory();                             // refresh the authoritative dit/bias estimate
        _pendingGapMs = -1.0f;                              // recorded
    }

    if (durationMs > _ditMs * 4.5f) {
        // Far longer than even a generously-bounded single dash: this
        // source (some web-based CW generators synthesize a whole letter
        // as one continuous audio clip) likely didn't produce real
        // silence between this letter's own elements, so what should
        // have been several separate marks fused into one continuous
        // "on" reading. Reconstruct the most likely dot/dash sequence
        // from the total duration using the current dit estimate rather
        // than reporting one wrong oversized mark. The dit estimate
        // itself is left alone here -- a guessed decomposition isn't a
        // reliable timing reference the way a real, cleanly-bounded
        // element is, so it's also never pushed into the rolling
        // history/fit below.
        float remaining = durationMs;  // how much of the fused duration is left to account for
        int guard        = 0;           // safety cap on loop iterations, in case of a pathological duration
        while (remaining > _ditMs * 0.5f && guard++ < 12) {  // keep decomposing while a plausible element remains
            if (remaining >= _ditMs * 2.0f) {                   // enough left for it to plausibly be a dash
                _currentSymbol += '-';                             // append a guessed dash to the live display string
                // A decomposed element is a guess, not a real
                // per-element timing measurement, so it's recorded as
                // moderately (not fully) confident -- still lets a
                // table entry that disagrees with one guessed symbol
                // win on the strength of its other elements, rather
                // than the guess being treated as certain.
                _currentElementProbs.push_back({0.15f, 0.85f});     // moderate confidence toward "dash"
                remaining -= _ditMs * 3.0f;                         // account for a dash-length chunk of the total
            } else {                                              // only enough left for it to plausibly be a dot
                _currentSymbol += '.';                              // append a guessed dot to the live display string
                _currentElementProbs.push_back({0.85f, 0.15f});     // moderate confidence toward "dot"
                remaining -= _ditMs * 1.0f;                         // account for a dot-length chunk of the total
            }
        }
        CW_LOG("[elem] FUSED dur=%.1fms dit=%.1fms -> symbol=\"%s\"\n", durationMs, _ditMs,
               _currentSymbol.c_str());  // log the fused duration and its guessed decomposition
    } else {
        char elem = classifyMark(durationMs);  // hard classification + confidence recording (doesn't touch _ditMs/_bias)
        _currentSymbol += elem;                 // append to the live display string

        pushHistory(durationMs, true);  // record this clean measurement for the rolling timing fit
        fitTimingFromHistory();          // refresh the authoritative dit/bias estimate from the updated history

        CW_LOG("[elem] %c dur=%.1fms dit->%.1fms bias=%.1fms symbol=\"%s\"\n", elem, durationMs, _ditMs, _bias,
               _currentSymbol.c_str());  // log the classification and the fit's resulting estimate
    }

    _markEndUs        = nowUs;   // remember when this mark ended, for the next gap measurement
    _haveMarkEnd       = true;    // a mark has now ended at least once
    _letterFlushed     = false;   // a new element means the current letter hasn't been flushed yet
    _wordSpaceFlushed  = false;   // likewise for the current word
}

void CwDecoder::pushHistory(float ms, bool isMark) {
    _history[_historyPos] = {ms, isMark};           // write this entry at the ring buffer's current cursor
    _historyPos = (_historyPos + 1) % kHistoryCap;    // advance the cursor, wrapping at the end of the buffer
    if (_historyLen < kHistoryCap) {                   // still filling up for the first time
        ++_historyLen;                                   // grow the valid-entry count until the buffer is full
    }
}

void CwDecoder::fitTimingFromHistory() {
    // Global least-squares fit over the rolling element history, adopted
    // from "morse1981" (see docs/PROJECT_LOG.md): replaces the old
    // purely-reactive per-element EMA as the authority for _ditMs, and
    // introduces _bias (envelope-decay/detector-lag compensation)
    // alongside it. A single ambiguous or noise-corrupted element barely
    // moves a fit over ~70 data points, which is naturally resistant to
    // the exact kind of one-direction compounding drift that took three
    // separate patches (0.4.8, 0.4.10, 0.4.12) to defend the old EMA
    // against.
    if (_historyLen < 8) return;  // not enough data yet for a meaningful fit; keep whatever _ditMs/_bias already hold

    float bestErr  = 1.0e30f;  // lowest total error found so far across every (u, d) candidate tried
    float bestDit  = _ditMs;    // dit value achieving bestErr; seeded at the current value in case nothing scores better
    float bestBias = _bias;     // bias value achieving bestErr; seeded likewise

    // Grid search 8-60 WPM in 1.0 WPM steps (morse1981 itself uses 0.5
    // WPM steps and a slightly finer bias grid; this project's steps are
    // coarsened for speed on an embedded target -- the damped blend
    // below smooths over the coarser resolution regardless), converted
    // to dit length in ms via the standard PARIS-timing relationship.
    for (float wpmCandidate = 8.0f; wpmCandidate <= 60.0f; wpmCandidate += 1.0f) {  // every candidate speed
        float u = 1200.0f / wpmCandidate;  // candidate dit length, in ms, for this WPM

        // Bias search range scaled to the candidate dit -- detector lag
        // can't plausibly exceed a modest fraction of one dit-unit.
        for (float d = -0.3f * u; d <= 0.3f * u; d += 0.1f * u) {  // every candidate bias offset for this dit
            float err = 0.0f;  // accumulated error for this (u, d) candidate, across the whole history

            for (int i = 0; i < _historyLen; ++i) {  // every recorded element duration
                const HistEntry& e = _history[i];      // one recorded element duration
                float best1;                            // best (lowest) per-element error across this entry's candidate multiples

                if (e.isMark) {
                    // A mark measures long by ~bias, so its expected
                    // value under this candidate is the ideal multiple
                    // plus d.
                    float expDot  = u + d;                     // expected duration if this mark is really a dot
                    float expDash = 3.0f * u + d;                // expected duration if this mark is really a dash
                    float errDot  = fabsf(e.ms - expDot) / u;    // normalized error against the dot hypothesis
                    float errDash = fabsf(e.ms - expDash) / u;   // normalized error against the dash hypothesis
                    best1         = std::min(errDot, errDash);   // take whichever hypothesis fits better
                } else {
                    // A gap measures short by ~bias, so its expected
                    // value under this candidate is the ideal multiple
                    // minus d.
                    float expElem   = u - d;                          // expected duration if this gap is really an intra-element gap
                    float expLetter = 3.0f * u - d;                    // expected duration if this gap is really a letter gap
                    float expWord   = 7.0f * u - d;                     // expected duration if this gap is really a word gap
                    float errElem   = fabsf(e.ms - expElem) / u;        // normalized error against the element-gap hypothesis
                    float errLetter = fabsf(e.ms - expLetter) / u;      // normalized error against the letter-gap hypothesis
                    float errWord   = fabsf(e.ms - expWord) / u;         // normalized error against the word-gap hypothesis
                    best1           = std::min(errElem, std::min(errLetter, errWord));  // take whichever hypothesis fits best
                }

                err += std::min(best1, 1.0f);  // cap each element's contribution, so one wild outlier can't dominate the whole fit
            }

            if (err < bestErr) {  // this (u, d) candidate scores better than anything seen so far
                bestErr  = err;     // remember its error
                bestDit  = u;       // remember its dit length
                bestBias = d;       // remember its bias
            }
        }
    }

    // Damped blend into the authoritative estimate, rather than jumping
    // straight to the best-fit values every time -- keeps the estimate
    // smooth from one fit to the next instead of chasing whichever
    // single fit happened to run last. "Fast speed lock" (0.5.0,
    // morse1981 full-UI-replica request) raises this weight so new fits
    // are adopted much more aggressively, converging to the sender's
    // real speed in fewer elements at the cost of a less stable estimate
    // once converged.
    float fitBlendWeight = _fastSpeedLock ? 0.6f : 0.25f;                        // how much of each new fit result to adopt per call
    _ditMs = _ditMs * (1.0f - fitBlendWeight) + bestDit * fitBlendWeight;   // blend the dit estimate toward this fit's result
    _bias  = _bias * (1.0f - fitBlendWeight) + bestBias * fitBlendWeight;   // blend the bias estimate toward this fit's result

    // Keep both within sane absolute bounds regardless of what the fit
    // produced, the same belt-and-suspenders spirit as the old EMA's own
    // clamps (0.4.10/0.4.12) -- a fit over noisy real-world data is not
    // infallible either.
    _ditMs = clampf(_ditMs, 24.0f, 260.0f);                  // matches Morse Expert's documented ~12-45 WPM span with margin
    _bias  = clampf(_bias, -0.3f * _ditMs, 0.3f * _ditMs);    // bias can't plausibly exceed a modest fraction of one dit-unit
}

void CwDecoder::checkSyncLoss() {
    // Adopted from "morse1981" (see docs/PROJECT_LOG.md): watch the
    // decoded text itself for signs the timing estimate has derailed,
    // rather than relying solely on the signal-processing layer to
    // notice -- a capability this project had no equivalent of before.
    // Two symptoms, matching the reference's own: a run of degenerate
    // single-letter characters (the ones a garbled/drifting classifier
    // tends to default to), and short "words" made up only of the
    // easiest-to-falsely-trigger letters.
    constexpr int kTailWindow = 24;  // how many recent characters to inspect, matching morse1981's own window

    size_t len   = _decodedText.size();                            // total transcript length so far
    size_t start = (len > kTailWindow) ? len - kTailWindow : 0;      // start index of the recent tail window
    std::string tail = _decodedText.substr(start);                   // the recent tail itself

    // Degenerate-run check: 4 or more consecutive characters drawn only
    // from the set a garbled/drifting classifier tends to default to.
    const std::string kDegenerateSet = "ETIMSOH50~";  // '~' is this project's own unclassified-fallback marker
    int runLen          = 0;      // current consecutive-degenerate-character run length
    bool degenerateRun  = false;  // whether a run of 4+ was found anywhere in the tail
    for (char c : tail) {                                       // every character in the tail, in order
        if (kDegenerateSet.find(c) != std::string::npos) {         // this character is one of the "weak" ones
            ++runLen;                                                // extend the current run
            if (runLen >= 4) degenerateRun = true;                    // four or more in a row is long enough to be suspicious
        } else {                                                    // any other character
            runLen = 0;                                                // breaks the run
        }
    }

    // Suspect-word check: split the tail on spaces, count words of
    // length >= 2 made up only of E/T/I (the shortest, most ambiguous
    // letters) that aren't a small set of real short words that happen
    // to be spelled that way.
    static const std::vector<std::string> kAllowedEtiWords = {"TIE", "TIT", "IT"};  // legitimate short real words in this alphabet
    int suspectWords = 0;  // count of suspect ETI-only words found in the tail
    size_t pos        = 0;  // scan cursor through the tail string
    while (pos < tail.size()) {                                                     // still more of the tail to scan
        size_t spacePos = tail.find(' ', pos);                                        // next space, or npos if this is the last word
        std::string word =
            tail.substr(pos, spacePos == std::string::npos ? std::string::npos : spacePos - pos);  // one word
        if (word.size() >= 2) {                                                        // only length >= 2 words are worth checking
            bool onlyEti = true;                                                          // whether this word is made up only of E/T/I
            for (char c : word) {                                                          // every character in this word
                if (c != 'E' && c != 'T' && c != 'I') {                                       // found a non-ETI character
                    onlyEti = false;                                                             // this word doesn't qualify as suspect
                    break;                                                                        // no need to check the rest
                }
            }
            bool allowed = false;                                                          // whether this word is on the legitimate-words allowlist
            for (const auto& w : kAllowedEtiWords) {                                         // every allowed word
                if (w == word) {                                                                // this word matches a known real one
                    allowed = true;                                                                // it's not suspect after all
                    break;                                                                          // no need to check the rest
                }
            }
            if (onlyEti && !allowed) ++suspectWords;  // an ETI-only word that isn't a known real one counts as suspect
        }
        if (spacePos == std::string::npos) break;  // reached the end of the tail; nothing more to scan
        pos = spacePos + 1;                          // continue scanning just past this space
    }

    if (degenerateRun || suspectWords >= 2) {
        // The timing estimate looks derailed -- clear the rolling
        // history so the next fit starts fresh from new elements only,
        // rather than being dragged by whatever bad data caused this.
        // _ditMs itself is deliberately left alone rather than reset to
        // a neutral seed: this project's own history (0.4.9-0.4.12) shows
        // resetting the speed estimate outright can cause worse harm than
        // it fixes, so the safer half-measure is to stop trusting old
        // history while keeping the last known estimate as a starting
        // point for the next fit.
        CW_LOG("[sync] possible derailment (degenerateRun=%d suspectWords=%d) -- clearing history\n",
               degenerateRun ? 1 : 0, suspectWords);  // log the trigger for visibility
        _historyLen = 0;     // discard all recorded history
        _historyPos = 0;     // restart the ring-buffer write cursor
        _bias       = 0.0f;  // reset the lag-compensation term, since it's specifically what a bad fit would have corrupted
    }
}

void CwDecoder::finalizeLetterIfAny() {
    if (_currentSymbol.empty()) return;  // nothing to finalize
    char c = decodeMorseProbabilistic(_currentElementProbs.data(), (int)_currentElementProbs.size());  // best-matching letter, or '\0'
    // '~' marks a pattern that matched nothing at all, including the
    // long-dot-run '#' case handled in decodeMorseProbabilistic() -- kept
    // visually distinct from a real, matched '#' so the two aren't
    // conflated when reading decoded text or these logs.
    CW_LOG("[letter] \"%s\" -> '%c'\n", _currentSymbol.c_str(), c ? c : '~');  // log the raw symbol and its decoded letter
    _decodedText += c ? c : '~';  // append the decoded (or fallback) character to the transcript
    _lastFinalizedSymbol = _currentSymbol;  // remember this pattern for currentSymbol() under "buffered output"
    _currentSymbol.clear();        // clear the in-progress display string for the next letter
    _currentElementProbs.clear();  // clear the in-progress confidence list for the next letter

    checkSyncLoss();  // watch the freshly-updated transcript for signs of derailment

    constexpr size_t kMaxText = 4000;  // trim threshold, to bound memory use over a long session
    constexpr size_t kTrimTo  = 3000;  // size to trim back down to once the threshold is hit
    if (_decodedText.size() > kMaxText) {                          // transcript has grown too long
        _decodedText.erase(0, _decodedText.size() - kTrimTo);        // drop the oldest characters, keeping the most recent kTrimTo
    }
}

void CwDecoder::tick() {
    if (_toneOn || !_haveMarkEnd) return;  // nothing to check while a mark is actively sounding, or before any mark has ever ended

    float gapMs      = (clockUs() - _markEndUs) / 1000.0f;  // raw elapsed silence since the last mark ended
    float correctedMs = gapMs + _bias;  // a gap measures short by ~bias (decay eats into its front edge), so add it back

    if (!_letterFlushed && correctedMs > _ditMs * kLetterGapUnits) {  // long enough (after bias correction) to end the current letter
        CW_LOG("[gap] letter gapMs=%.1f corrected=%.1f thresh=%.1f (dit=%.1f bias=%.1f)\n", gapMs, correctedMs,
               _ditMs * kLetterGapUnits, _ditMs, _bias);  // log the measured and corrected gap alongside the threshold
        finalizeLetterIfAny();   // match and append whatever letter was in progress
        _letterFlushed = true;   // don't finalize the same (now-empty) letter again until a new element arrives
    }
    if (!_wordSpaceFlushed && correctedMs > _ditMs * kWordGapUnits) {  // long enough (after bias correction) to also end the current word
        if (!_decodedText.empty() && _decodedText.back() != ' ') {       // avoid stacking up multiple spaces in a row
            _decodedText += ' ';                                           // mark the word boundary in the transcript
        }
        _wordSpaceFlushed = true;  // don't add another space until a new word actually starts
    }
}

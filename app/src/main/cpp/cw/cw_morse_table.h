// Morse code table and probabilistic matching for the shared CW decoder (cw_decoder.h) - from the Tab5CWDecoder project.
#pragma once

// International Morse code table plus common punctuation. Patterns are
// matched against a per-element probability sequence (see ElementProb)
// rather than a committed '.'/'-' string, so lookup is a linear scan over
// ~50 short entries scoring each by joint probability (cheap, and only
// runs once per decoded letter).

struct MorseEntry {
    const char* pattern;
    char ch;
};

static const MorseEntry kMorseTable[] = {
    {".-", 'A'},    {"-...", 'B'},  {"-.-.", 'C'},  {"-..", 'D'},   {".", 'E'},
    {"..-.", 'F'},  {"--.", 'G'},   {"....", 'H'},  {"..", 'I'},    {".---", 'J'},
    {"-.-", 'K'},   {".-..", 'L'},  {"--", 'M'},    {"-.", 'N'},    {"---", 'O'},
    {".--.", 'P'},  {"--.-", 'Q'},  {".-.", 'R'},   {"...", 'S'},   {"-", 'T'},
    {"..-", 'U'},   {"...-", 'V'},  {".--", 'W'},   {"-..-", 'X'},  {"-.--", 'Y'},
    {"--..", 'Z'},
    {"-----", '0'}, {".----", '1'}, {"..---", '2'}, {"...--", '3'}, {"....-", '4'},
    {".....", '5'}, {"-....", '6'}, {"--...", '7'}, {"---..", '8'}, {"----.", '9'},
    {".-.-.-", '.'}, {"--..--", ','}, {"..--..", '?'}, {".----.", '\''},
    {"-.-.--", '!'}, {"-..-.", '/'},  {"-.--.", '('},  {"-.--.-", ')'},
    {".-...", '&'},  {"---...", ':'}, {"-.-.-.", ';'}, {"-...-", '='},
    {".-.-.", '+'},  {"-....-", '-'}, {"..--.-", '_'}, {".-..-.", '"'},
    {"...-..-", '$'}, {".--.-.", '@'},
};

static const int kMorseTableSize = sizeof(kMorseTable) / sizeof(kMorseTable[0]);

// One observed mark's probability of being a dit vs. a dash, in [0,1]
// (pDash == 1 - pDit; kept explicit so call sites read naturally either
// way). Produced by CwDecoder::classifyMark() from the mark's duration
// relative to the current dit-length estimate.
struct ElementProb {
    float pDit;
    float pDash;
};

// Bayesian counterpart to a hard '.'/'-' string lookup: rather than
// committing each element to a single symbol before matching even starts,
// scores every table entry of the same element *count* by the joint
// probability of the whole observed sequence under that entry's known
// pattern, and returns whichever scores highest. An element whose
// duration sits right on the dit/dash boundary -- exactly what a room's
// reverb tends to produce, stretching or padding real marks -- degrades
// gracefully into "probably a dit, but could be a dash" instead of being
// forced into one possibly-wrong hard symbol that a subsequent exact
// string match then either matches or rejects outright. This is the
// technique documented for VE3NEA's CW Skimmer and its Android port Morse
// Expert: express prior knowledge (the codebook) and observations
// (per-element probabilities) as probabilities and combine them with
// Bayes' rule, rather than making a hard decision at every element.
inline char decodeMorseProbabilistic(const ElementProb* probs, int len) {
    if (len <= 0) return '\0';

    // No standard letter or digit has 6+ consecutive dots (the longest,
    // '5', is exactly 5), so a longer unbroken high-confidence-dot run
    // isn't a misclassification -- it's very likely a source rendering a
    // literal '#' character as the standard ham-radio 8-dot "ERROR"
    // prosign (the exact count some sources use can vary a little).
    bool allDots = true;
    for (int i = 0; i < len; ++i) {
        if (probs[i].pDit < probs[i].pDash) {
            allDots = false;
            break;
        }
    }
    if (allDots && len >= 6) return '#';

    char best      = '\0';
    float bestScore = -1.0f;
    for (int i = 0; i < kMorseTableSize; ++i) {
        const char* pattern = kMorseTable[i].pattern;
        int patLen = 0;
        for (const char* p = pattern; *p; ++p) ++patLen;
        if (patLen != len) continue;

        float score = 1.0f;
        for (int j = 0; j < len; ++j) {
            score *= (pattern[j] == '.') ? probs[j].pDit : probs[j].pDash;
        }
        if (score > bestScore) {
            bestScore = score;
            best      = kMorseTable[i].ch;
        }
    }
    return best;  // '\0' if no table entry has this many elements
}

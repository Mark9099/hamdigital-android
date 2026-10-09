// ----------------------------------------------------------------------------
// kb_tx.cxx  --  RTTY and PSK31 / 63 / 125 transmit audio for a whole message, following fldigi's transmitters (src/cw_rtty/rtty.cxx
// send_char / baudot_enc, src/psk/psk.cxx tx_symbol / tx_char / tx_flush): PSK31 = an idle preamble of phase
// reversals (which switches receivers' DCD on), each character's PSK varicode followed by "00", a postamble of steady
// carrier (DCD off), with fldigi's raised-cosine amplitude shaping between symbols; RTTY = Baudot with LTRS / FIGS
// shifts (FIGS sent again after a space, for receivers using unshift-on-space), a start bit (space), 5 data bits and
// 1.5 stop bits (mark), as continuous-phase FSK with mark the higher tone. (HF Digital Modes; GPL v3 as fldigi.)
// ----------------------------------------------------------------------------
#include "kb_tx.h"                                   // this file's API
#include <cmath>                                     // sin, cos
#include "pskvaricode.h"                             // psk_varicode_encode (fldigi)

static const double SR = 8000;                       // fldigi's sample rate for these modes

std::vector<int16_t> psk31_tx_audio(const std::string &text, double f0, double amplitude, int speed)
{
    const int sl = speed == 125 ? 64 : speed == 63 ? 128 : 256; // samples a symbol (fldigi: 31.25, 62.5, 125 baud)
    const int amble = 256 / sl * 32;                 // preamble / postamble symbols (fldigi: dcdbits - 32, 64, 128)
    std::vector<int> syms;                           // 1 = no phase change, 0 = a reversal
    for (int i = 0; i < amble; i++) syms.push_back(0); // preamble (fldigi: dcdbits of reversals)
    for (unsigned char c : text) {                   // each character: its varicode, then 00
        if (c == '\n') c = '\r';                     // (PSK31 new line: CR then LF)
        const char *v = psk_varicode_encode(c);
        for (const char *p = v; *p; p++) syms.push_back(*p == '1');
        syms.push_back(0); syms.push_back(0);
        if (c == '\r') { const char *lf = psk_varicode_encode('\n'); for (const char *p = lf; *p; p++) syms.push_back(*p == '1'); syms.push_back(0); syms.push_back(0); }
    }
    for (int i = 0; i < amble; i++) syms.push_back(1); // postamble: steady carrier (fldigi tx_flush)
    std::vector<int16_t> out; out.reserve(syms.size() * sl + sl);
    double ph = 0, prev = 0, cur = 0;                // amplitudes: start from silence
    double const dph = 2 * M_PI * f0 / SR;
    bool first = true;
    for (int s : syms) {
        prev = cur;
        if (first) { cur = 1; first = false; } else if (!s) cur = -cur; // a 0 reverses the phase
        for (int i = 0; i < sl; i++) {               // fldigi's tx_shape: 0.5 cos(i pi / sl) + 0.5 from the old to the new
            double sh = 0.5 * cos(i * M_PI / sl) + 0.5;
            double a = prev * sh + cur * (1 - sh);
            out.push_back((int16_t)(a * sin(ph) * amplitude * 32767)); ph += dph; if (ph > 2 * M_PI) ph -= 2 * M_PI;
        }
    }
    for (int i = 0; i < sl; i++) {                   // and down to silence
        double sh = 0.5 * cos(i * M_PI / sl) + 0.5;
        out.push_back((int16_t)(cur * sh * sin(ph) * amplitude * 32767)); ph += dph;
    }
    return out;
}

// Baudot (ITA2) letters and U.S. figures, as fldigi's tables
static const char LETTERS[] = "\0E\nA SIU\rDRJNFCKTZLWHYPQOBG\0MXV\0";
static const char FIGURES[] = "\0" "3\n- \a87\r$4',!:(5\")2#6019?&\0./;\0";

std::vector<int16_t> rtty_tx_audio(const std::string &text, double f0, double shift, double baud, double amplitude)
{
    std::vector<int> codes;                          // Baudot codes to send
    bool figs = false;                               // the shift state the receiver is in
    for (int i = 0; i < 4; i++) codes.push_back(0x1F); // LTRS: lets the receiver settle (fldigi's preamble idea)
    for (char ch : text) {
        char c = (char)toupper((unsigned char)ch);
        if (c == '\n') { codes.push_back(0x08); codes.push_back(0x02); continue; } // CR LF
        if (c == ' ') { codes.push_back(0x04); figs = false; continue; } // space; receivers return to letters (unshift on space)
        int l = -1, f = -1;
        for (int k = 1; k < 32; k++) { if (LETTERS[k] == c) l = k; if (FIGURES[k] == c) f = k; }
        if (l > 0 && c != ' ') { if (figs) { codes.push_back(0x1F); figs = false; } codes.push_back(l); }
        else if (f > 0) { if (!figs) { codes.push_back(0x1B); figs = true; } codes.push_back(f); }
        // (anything else cannot be sent in Baudot: left out)
    }
    for (int i = 0; i < 2; i++) codes.push_back(0x1F); // LTRS at the end
    std::vector<int16_t> out;
    double const sps = SR / baud;                    // samples a bit (176 at 45.45 baud)
    double t = 0, ph = 0;                            // samples written (fractional), phase
    auto bit = [&](int mark, double nbits) {         // one bit (or 1.5): continuous-phase FSK
        int n = (int)(t + nbits * sps) - (int)t; t += nbits * sps;
        double f = mark ? f0 + shift / 2 : f0 - shift / 2; // mark the higher tone (USB)
        for (int i = 0; i < n; i++) { out.push_back((int16_t)(sin(ph) * amplitude * 32767)); ph += 2 * M_PI * f / SR; if (ph > 2 * M_PI) ph -= 2 * M_PI; }
    };
    bit(1, 8);                                       // mark idle first
    for (int c : codes) { bit(0, 1); for (int k = 0; k < 5; k++) bit((c >> k) & 1, 1); bit(1, 1.5); } // start, data (LSB first), stop
    bit(1, 4);                                       // mark idle last
    int r = 80;                                      // 10 ms ramps (no key clicks)
    for (int i = 0; i < r && i < (int)out.size(); i++) { double e = 0.5 - 0.5 * cos(M_PI * i / r); out[i] = (int16_t)(out[i] * e); out[out.size() - 1 - i] = (int16_t)(out[out.size() - 1 - i] * e); }
    return out;
}

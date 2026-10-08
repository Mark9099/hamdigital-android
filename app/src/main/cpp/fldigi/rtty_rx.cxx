// ----------------------------------------------------------------------------
// rtty_rx.cxx  --  RTTY receiver: fldigi's rtty modem (src/cw_rtty/rtty.cxx, fldigi 4.1.23), receive side only. The
// demodulator is fldigi's unchanged: the signal is mixed down at the mark and at the space frequency, each through
// fldigi's fftfilt RTTY filter, envelope and noise trackers, the "optimal ATC" decision, fldigi's start-bit / data /
// stop-bit state machine, Baudot decoding (unshift on space) and its AFC from the mark tone's phase. Removed: the user
// interface (scopes, viewers, status lines), settings (now set_params / set_freq / ...), Synop decoding and transmit.
// Changed: the signal quality (Metric) came from fldigi's waterfall; here it comes from the filters' own envelopes.
// See ANDROID_CHANGES.txt.
//
// Copyright (C) 2012
//		Dave Freese, W1HKJ
//		Stefan Fendt, DL1SMF
// (receive-only edition 2026, HF Digital Modes)
//
// This code bears some resemblance to code contained in gmfsk from which it originated. Much has been changed, but
// credit should still be given to Tomi Manninen (oh2bns@sral.fi), who so graciously distributed his gmfsk modem
// under the GPL.
//
// This is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License
// as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later
// version. It is distributed WITHOUT ANY WARRANTY; see the GNU General Public License (LICENSE).
// ----------------------------------------------------------------------------
#include "rtty_rx.h"                                 // this class
#include <cmath>                                     // cos, log10
#include <cstdlib>                                   // abs
#include <algorithm>                                 // std::min
#include "misc.h"                                    // decayavg, clamp

#define LETTERS 0x100                                // Baudot shift states (fldigi)
#define FIGURES 0x200
#ifndef TWOPI
#define TWOPI (2.0 * M_PI)
#endif

// Baudot (ITA2), as fldigi
static const char letters[32] = {
    '\0', 'E', '\n', 'A', ' ', 'S', 'I', 'U',
    '\r', 'D', 'R', 'J', 'N', 'F', 'C', 'K',
    'T', 'Z', 'L', 'W', 'H', 'Y', 'P', 'Q',
    'O', 'B', 'G', ' ', 'M', 'X', 'V', ' '
};
// U.S. version of the figures case (fldigi's)
static const char figures[32] = {
    '\0', '3', '\n', '-', ' ', '\a', '8', '7',
    '\r', '$', '4', '\'', ',', '!', ':', '(',
    '5', '"', ')', '2', '#', '6', '0', '1',
    '9', '?', '&', ' ', '.', '/', ';', ' '
};

RttyRx::RttyRx()
{
    set_params(170, 45.45, 5, PARITY_NONE, 1.5);     // the amateur standard
}

RttyRx::~RttyRx()
{
    delete mark_filt; delete space_filt;             // the filters
}

void RttyRx::reset_filters()                         // fldigi rtty::reset_filters()
{
    delete mark_filt;
    mark_filt = new fftfilt(rtty_baud / samplerate, filter_length);
    mark_filt->rtty_filter(rtty_baud / samplerate);
    delete space_filt;
    space_filt = new fftfilt(rtty_baud / samplerate, filter_length);
    space_filt->rtty_filter(rtty_baud / samplerate);
}

void RttyRx::set_params(double sh, double baud, int bits, Parity parity, double stop_bits) // fldigi rtty::restart(), receive part
{
    (void)stop_bits;                                 // (the receiver only needs the stop bit to be mark)
    shift = sh; rtty_baud = baud;
    filter_length = baud > 200 ? 64 : (baud > 150 ? 128 : (baud > 110 ? 256 : 512)); // fldigi's FILTLEN for the baud rate
    nbits = bits;
    rtty_parity = (bits == 5) ? PARITY_NONE : parity;
    symbollen = (int)(samplerate / rtty_baud + 0.5); // samples a bit
    reset_filters();
    mark_noise = space_noise = 0;
    bit = true;
    freqerr = 0.0;
    metric = 0.0;
    sigpwr = noisepwr = 0.0;
    rx_init();
}

void RttyRx::rx_init()                               // fldigi rtty::rx_init()
{
    rxstate = RX_IDLE;
    rxmode = LETTERS;
    for (int i = 0; i < RTTY_MAXBITS; i++) bit_buf[i] = false;
    mark_phase = 0; space_phase = 0;
    mark_mag = 0; space_mag = 0; mark_env = 0; space_env = 0;
    inp_ptr = 0;
    lastchar = 0;
    for (int i = 0; i < RTTY_MAXPIPE; i++) mark_history[i] = space_history[i] = cmplx(0, 0);
}

cmplx RttyRx::mixer(double &phase, double f, cmplx in) // fldigi rtty::mixer()
{
    cmplx z = cmplx(cos(phase), sin(phase)) * in;
    phase -= TWOPI * f / samplerate;
    if (phase < -TWOPI) phase += TWOPI;
    return z;
}

static int rparity(int c)                            // fldigi
{
    int w = c, p = 0;
    while (w) { p += (w & 1); w >>= 1; }
    return p & 1;
}

int RttyRx::parity_of(unsigned int c, int nb)        // fldigi rttyparity()
{
    c &= (1 << nb) - 1;
    switch (rtty_parity) {
    default:
    case PARITY_NONE: return 0;
    case PARITY_ODD: return rparity(c);
    case PARITY_EVEN: return !rparity(c);
    case PARITY_ZERO: return 0;
    case PARITY_ONE: return 1;
    }
}

int RttyRx::decode_char()                            // fldigi rtty::decode_char()
{
    unsigned int parbit = (rxdata >> nbits) & 1;
    unsigned int par = parity_of(rxdata, nbits);
    if (rtty_parity != PARITY_NONE && parbit != par) return 0;
    unsigned int data = rxdata & ((1 << nbits) - 1);
    if (nbits == 5) return baudot_dec(data);
    return data;
}

char RttyRx::baudot_dec(unsigned char data)          // fldigi rtty::baudot_dec(), unshift-on-space on (fldigi's default)
{
    int out = 0;
    switch (data) {
    case 0x1F: rxmode = LETTERS; break;              // letters
    case 0x1B: rxmode = FIGURES; break;              // figures
    case 0x04: rxmode = LETTERS; return ' ';         // unshift-on-space
    default: out = (rxmode == LETTERS) ? letters[data] : figures[data]; break;
    }
    return out;
}

bool RttyRx::is_mark_space(int &correction)          // fldigi rtty::is_mark_space()
{
    correction = 0;
    if (bit_buf[0] && !bit_buf[symbollen - 1]) {     // rough bit position
        for (int i = 0; i < symbollen; i++) correction += bit_buf[i]; // mark/space straddle point
        if (abs(symbollen / 2 - correction) < 6) return true; // too small & bad signals are not decoded
    }
    return false;
}

bool RttyRx::is_mark() { return bit_buf[symbollen / 2]; } // fldigi rtty::is_mark()

bool RttyRx::rx(bool b)                              // fldigi rtty::rx()
{
    bool flag = false;
    unsigned char c = 0;
    int correction;
    for (int i = 1; i < symbollen; i++) bit_buf[i - 1] = bit_buf[i];
    bit_buf[symbollen - 1] = b;
    switch (rxstate) {
    case RX_IDLE:
        if (is_mark_space(correction)) { rxstate = RX_START; counter = correction; }
        break;
    case RX_START:
        if (--counter == 0) {
            if (!is_mark()) { rxstate = RX_DATA; counter = symbollen; bitcntr = 0; rxdata = 0; }
            else rxstate = RX_IDLE;
        }
        break;
    case RX_DATA:
        if (--counter == 0) { rxdata |= is_mark() << bitcntr++; counter = symbollen; }
        if (bitcntr == nbits + (rtty_parity != PARITY_NONE ? 1 : 0)) rxstate = RX_STOP;
        break;
    case RX_STOP:
        if (--counter == 0) {
            if (is_mark()) {
                if (squelch <= 0 || metric >= squelch) { // (fldigi: squelch slider)
                    c = decode_char();
                    if (c != 0) {
                        // supress <CR><CR> and <LF><LF> sequences (observed during the RTTY contest 2/9/2013)
                        if (c == '\r' && lastchar == '\r');
                        else if (c == '\n' && lastchar == '\n');
                        else text += (char)c;            // (fldigi: put_rx_char)
                        lastchar = c;
                    }
                    flag = true;
                }
            }
            rxstate = RX_IDLE;
        }
        break;
    default: break;
    }
    return flag;
}

// Signal quality. fldigi measured the power density at the mark and space tones and between them on its waterfall;
// here the mark and space filters' envelopes against their noise floors give the same kind of figure.
void RttyRx::Metric()
{
    double sp = mark_env * mark_env + space_env * space_env + 1e-20; // the tones
    double np = 2 * noise_floor * noise_floor + 1e-20; // the noise in the same filters
    sigpwr = decayavg(sigpwr, sp, sp > sigpwr ? 2 : 8); // (fldigi's averaging)
    noisepwr = decayavg(noisepwr, np, 16);
    snr_db = 10 * log10(sigpwr / noisepwr);
    metric = clamp(snr_db * 5.0, 0.0, 100.0);        // 20 dB = 100
}

void RttyRx::rx_process(const double *buf, int len)  // fldigi rtty::rx_process(), the demodulator unchanged
{
    const double *buffer = buf;
    int length = len;
    cmplx z, zmark, zspace, *zp_mark, *zp_space;
    int n_out = 0;

    while (length-- > 0) {
        z = cmplx(*buffer, *buffer);                 // analytic signal from the sound card samples (as fldigi)
        buffer++;
        // Mix it with the audio carrier frequency to create two baseband signals; mark and space are separated and
        // processed independently by lowpass windowed-sinc overlap-add filters of the same size, in sync.
        zmark = mixer(mark_phase, frequency + shift / 2.0, z);
        mark_filt->run(zmark, &zp_mark);
        zspace = mixer(space_phase, frequency - shift / 2.0, z);
        n_out = space_filt->run(zspace, &zp_space);

        for (int i = 0; i < n_out; i++) {
            mark_mag = abs(zp_mark[i]);
            mark_env = decayavg(mark_env, mark_mag, (mark_mag > mark_env) ? symbollen / 4 : symbollen * 16);
            mark_noise = decayavg(mark_noise, mark_mag, (mark_mag < mark_noise) ? symbollen / 4 : symbollen * 48);
            space_mag = abs(zp_space[i]);
            space_env = decayavg(space_env, space_mag, (space_mag > space_env) ? symbollen / 4 : symbollen * 16);
            space_noise = decayavg(space_noise, space_mag, (space_mag < space_noise) ? symbollen / 4 : symbollen * 48);
            noise_floor = std::min(space_noise, mark_noise);

            // clipped
            double mclipped = mark_mag > mark_env ? mark_env : mark_mag;
            double sclipped = space_mag > space_env ? space_env : space_mag;
            if (mclipped < noise_floor) mclipped = noise_floor;
            if (sclipped < noise_floor) sclipped = noise_floor;

            // Optimal ATC (fldigi's decoder)
            double v3 = (mclipped - noise_floor) * (mark_env - noise_floor) -
                        (sclipped - noise_floor) * (space_env - noise_floor) - 0.25 * (
                        (mark_env - noise_floor) * (mark_env - noise_floor) -
                        (space_env - noise_floor) * (space_env - noise_floor));
            bit = v3 > 0;

            mark_history[inp_ptr] = zp_mark[i];
            space_history[inp_ptr] = zp_space[i];
            inp_ptr = (inp_ptr + 1) % RTTY_MAXPIPE;

            // detect TTY signal transitions; rx(...) returns true if a valid TTY bit stream was detected
            if (rx(reverse ? !bit : bit)) {
                int mp0 = inp_ptr - 2;
                int mp1 = mp0 + 1;
                if (mp0 < 0) mp0 += RTTY_MAXPIPE;
                if (mp1 < 0) mp1 += RTTY_MAXPIPE;
                double ferr = (TWOPI * samplerate / rtty_baud) *
                    (!reverse ? arg(conj(mark_history[mp1]) * mark_history[mp0]) :
                                arg(conj(space_history[mp1]) * space_history[mp0]));
                if (fabs(ferr) > rtty_baud / 2) ferr = 0;
                freqerr = decayavg(freqerr, ferr / 8, 8); // (fldigi's AFC speed "slow")
                if (afc_on && (squelch <= 0 || metric > squelch)) frequency = frequency - freqerr; // (fldigi: set_freq)
            }
        }
    }
    Metric();                                        // (fldigi: once a block)
}

std::string RttyRx::take_text()
{
    std::string t; t.swap(text); return t;           // hand over, start empty
}

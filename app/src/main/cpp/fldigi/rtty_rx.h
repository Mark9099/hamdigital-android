// ----------------------------------------------------------------------------
// rtty_rx.h  --  RTTY receiver: fldigi's rtty modem (src/cw_rtty/rtty.cxx), receive side only, without fldigi's
// user interface, settings and transmit code, for HF Digital Modes (Android). See ANDROID_CHANGES.txt.
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
#pragma once
#include <string>                                    // the decoded text
#include "complex.h"                                 // cmplx
#include "fftfilt.h"                                 // the mark and space filters
#include "filters.h"                                 // Cmovavg

#define RTTY_SampleRate 8000                         // fldigi's RTTY sample rate
#define RTTY_MAXPIPE 1024                            // (fldigi MAXPIPE)
#define RTTY_MAXBITS (2 * RTTY_SampleRate / 23 + 1)  // (fldigi MAXBITS)

class RttyRx {
public:
    enum Parity { PARITY_NONE = 0, PARITY_EVEN, PARITY_ODD, PARITY_ZERO, PARITY_ONE }; // (fldigi RTTY_PARITY)

    RttyRx();                                        // 45.45 baud, 170 Hz, 5 bits, 1.5 stop bits, at 1000 Hz
    ~RttyRx();
    void set_params(double shift, double baud, int bits, Parity parity, double stop_bits); // fldigi restart()
    void set_freq(double f) { frequency = f; }       // centre (between mark and space), audio Hz
    double get_freq() const { return frequency; }
    void set_reverse(bool r) { reverse = r; }        // mark and space swapped
    void set_afc(bool on) { afc_on = on; }           // follow the signal
    void set_squelch(double s) { squelch = s; }      // 0..100; 0 = off
    void rx_process(const double *buf, int len);     // audio in (8 kHz)
    std::string take_text();                         // the characters decoded since the last call
    double get_metric() const { return metric; }     // signal quality 0..100
    double get_snr_db() const { return snr_db; }     // signal to noise (dB)
    void reset() { rx_init(); }                      // start afresh (after a retune)

private:
    enum RxState { RX_IDLE = 0, RX_START, RX_DATA, RX_PARITY, RX_STOP, RX_STOP2 }; // (fldigi RTTY_RX_STATE)

    void rx_init();
    void reset_filters();
    cmplx mixer(double &phase, double f, cmplx in);
    int decode_char();
    char baudot_dec(unsigned char data);
    int parity_of(unsigned int c, int nbits);
    bool is_mark_space(int &correction);
    bool is_mark();
    bool rx(bool bit);
    void Metric();

    double samplerate = RTTY_SampleRate;
    double frequency = 1000;                         // centre frequency
    bool reverse = false, afc_on = true;
    double squelch = 0;
    double shift = 170, rtty_baud = 45.45;
    int nbits = 5, symbollen = 0, filter_length = 512;
    Parity rtty_parity = PARITY_NONE;
    double mark_noise = 0, space_noise = 0;
    bool bit = true;
    bool bit_buf[RTTY_MAXBITS];
    double mark_phase = 0, space_phase = 0;
    fftfilt *mark_filt = nullptr, *space_filt = nullptr;
    cmplx mark_history[RTTY_MAXPIPE], space_history[RTTY_MAXPIPE];
    int inp_ptr = 0;
    RxState rxstate = RX_IDLE;
    int counter = 0, bitcntr = 0, rxdata = 0;
    int rxmode = 0;                                  // LETTERS / FIGURES
    double mark_mag = 0, space_mag = 0, mark_env = 0, space_env = 0, noise_floor = 0;
    double sigpwr = 0, noisepwr = 0, metric = 0, snr_db = 0;
    double freqerr = 0;
    char lastchar = 0;
    std::string text;                                // decoded, not yet taken
};

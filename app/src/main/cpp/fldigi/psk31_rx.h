// ----------------------------------------------------------------------------
// psk31_rx.h  --  PSK31 receiver: fldigi's psk modem (src/psk/psk.cxx), BPSK31 receive only, without fldigi's user
// interface, settings, the other PSK variants and transmit code, for HF Digital Modes (Android). See
// ANDROID_CHANGES.txt.
//
// Copyright (C) 2006-2021
//		Dave Freese, W1HKJ
// Copyright (C) 2009-2010
//		John Douyere, VK2ETA
// Copyright (C) 2014-2021
//		John Phelps, KL4YFD
// (BPSK31 receive-only edition 2026, HF Digital Modes)
//
// Adapted from code contained in gmfsk source code distribution. gmfsk Copyright (C) 2001, 2002, 2003
// Tomi Manninen (oh2bns@sral.fi)
//
// This is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License
// as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later
// version. It is distributed WITHOUT ANY WARRANTY; see the GNU General Public License (LICENSE).
// ----------------------------------------------------------------------------
#pragma once
#include <string>                                    // the decoded text
#include "complex.h"                                 // cmplx
#include "filters.h"                                 // C_FIR_filter, Cmovavg, goertzel

#define PSK_NUM_FILTERS 4                            // (fldigi NUM_FILTERS)

class Psk31Rx {
public:
    Psk31Rx();                                       // BPSK31 at 1000 Hz, 8 kHz audio
    ~Psk31Rx();
    void set_freq(double f) { frequency = f; acquire = 3; } // carrier, audio Hz (a new signal: let the AFC pull in)
    double get_freq() const { return frequency; }
    void set_afc(bool on) { afc_on = on; }           // follow the signal
    void set_squelch(double s) { squelch = s; }      // 0..100; 0 = off (text from noise too)
    void rx_process(const double *buf, int len);     // audio in (8 kHz)
    std::string take_text();                         // the characters decoded since the last call
    double get_metric() const { return metric; }     // signal quality 0..100 (phase)
    double get_snr_db() const;                       // signal to noise (dB, fldigi's S/N figure)
    double get_imd_db() const;                       // IMD (dB; fldigi's figure)
    bool get_dcd() const { return dcd; }             // a signal is being decoded
    void reset() { rx_init(); }                      // start afresh (after a retune)

private:
    void rx_init();
    void rx_bit(int bit);
    void rx_symbol(cmplx symbol);
    void phaseafc();
    void afc();
    void signalquality();
    void calcSN_IMD(cmplx z);

    double samplerate = 8000;                        // fldigi's PSK sample rate
    int symbollen = 256;                             // samples a symbol (31.25 baud)
    int dcdbits = 32;                                // (fldigi, PSK31)
    double sc_bw;                                    // symbol rate (Hz)
    double frequency = 1000;
    bool afc_on = true;
    double squelch = 25;                             // (fldigi's squelch slider)
    double phaseacc = 0;
    cmplx prevsymbol = cmplx(1.0, 0.0);
    cmplx quality = cmplx(0.0, 0.0);
    unsigned int shreg = 0, dcdshreg = 0;
    bool dcd = false;
    int dcdOFFcounter = 0;
    double bitclk = 0;
    double syncbuf[16];
    double freqerr = 0, phase = 0;
    int bits = 0;
    double metric = 0, afcmetric = 0, averageamp = 0;
    int acquire = 0;
    C_FIR_filter *fir1 = nullptr, *fir2 = nullptr;
    Cmovavg *e0_filt, *e1_filt, *e2_filt, *e3_filt;
    goertzel *re_Gbin[PSK_NUM_FILTERS], *im_Gbin[PSK_NUM_FILTERS];
    bool reset_filters = true;
    double snratio = 1, imdratio = 0;
    std::string text;                                // decoded, not yet taken
};

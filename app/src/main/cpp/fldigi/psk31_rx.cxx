// ----------------------------------------------------------------------------
// psk31_rx.cxx  --  PSK31 / 63 / 125 receiver: fldigi's psk modem (src/psk/psk.cxx, fldigi 4.1.23), BPSK receive only. The
// demodulator is fldigi's unchanged: the NCO mix, the FIR filter pair (PSKcore, or sinc for PSK125; decimating to 16 samples a symbol), fldigi's symbol
// timing recovery (bitclk / syncbuf), the differential phase decision, its quality / DCD logic (the 0xAAAAAAAA idle
// preamble switches DCD on, a run of zeros off, else the squelch), PSK varicode decoding, the phase AFC, and the S/N
// and IMD measurement (Goertzel filters at the base, fundamental, 3rd and 4th harmonics). Removed: the user interface,
// settings (now set_freq / set_afc / set_squelch), the other PSK variants (QPSK, 8PSK, PSK-R, FEC, multi-carrier),
// the signal search (it used fldigi's waterfall) and transmit. See ANDROID_CHANGES.txt.
//
// Copyright (C) 2006-2021
//		Dave Freese, W1HKJ
// Copyright (C) 2009-2010
//		John Douyere, VK2ETA
// Copyright (C) 2014-2021
//		John Phelps, KL4YFD
// (BPSK31/63/125 receive-only edition 2026, HF Digital Modes)
//
// Adapted from code contained in gmfsk source code distribution. gmfsk Copyright (C) 2001, 2002, 2003
// Tomi Manninen (oh2bns@sral.fi)
//
// This is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License
// as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later
// version. It is distributed WITHOUT ANY WARRANTY; see the GNU General Public License (LICENSE).
// ----------------------------------------------------------------------------
#include "psk31_rx.h"                                // this class
#include <cmath>                                     // cos, sin, log10
#include "misc.h"                                    // decayavg, clamp
#include "pskcoeff.h"                                // FIRLEN, raisedcosfilt, pskcore_filter
#include "pskvaricode.h"                             // psk_varicode_decode

#define SQLDECAY 50                                  // (fldigi)
#ifndef TWOPI
#define TWOPI (2.0 * M_PI)
#endif

Psk31Rx::Psk31Rx(int speed)                          // fldigi psk::psk(MODE_PSK31 / 63 / 125), receive parts
{
    switch (speed) {                                 // fldigi's settings for each speed
    case 125: symbollen = 64;  dcdbits = 128; break; // PSK125 (fir_type SINC)
    case 63:  symbollen = 128; dcdbits = 64;  break; // PSK63 (PSK_CORE)
    default:  symbollen = 256; dcdbits = 32;  break; // PSK31 (PSK_CORE)
    }
    sc_bw = samplerate / symbollen;                  // the symbol rate: 31.25, 62.5, 125 Hz
    double fir1c[FIRLEN + 1], fir2c[FIRLEN + 1];
    fir1 = new C_FIR_filter(); fir2 = new C_FIR_filter();
    if (speed == 125) {                              // SINC: matched sin(x)/x filters with a Blackman window (fldigi)
        wsincfilt(fir1c, 1.0 / symbollen, FIRLEN);
        wsincfilt(fir2c, 1.0 / 16.0, FIRLEN);
        fir1->init(FIRLEN, symbollen / 16, fir1c, fir1c); // decimate to 16 samples a symbol
        fir2->init(FIRLEN, 1, fir2c, fir2c);
    } else {                                         // PSK_CORE
        raisedcosfilt(fir1c, FIRLEN);
        for (int i = 0; i <= FIRLEN; i++) fir2c[i] = pskcore_filter[i];
        fir1->init(FIRLEN + 1, symbollen / 16, fir1c, fir1c); // decimate to 16 samples a symbol
        fir2->init(FIRLEN + 1, 1, fir2c, fir2c);
    }
    e0_filt = new Cmovavg(dcdbits / 2); e1_filt = new Cmovavg(dcdbits / 2);
    e2_filt = new Cmovavg(dcdbits / 2); e3_filt = new Cmovavg(dcdbits / 2);
    re_Gbin[0] = new goertzel(160, 0, 500.0);        // base
    re_Gbin[1] = new goertzel(160, 15.625, 500.0);   // fundamental
    re_Gbin[2] = new goertzel(160, 62.5, 500.0);     // 4th harmonic (noise)
    re_Gbin[3] = new goertzel(160, 46.875, 500.0);   // 3rd harmonic (imd)
    im_Gbin[0] = new goertzel(160, 0, 500.0);
    im_Gbin[1] = new goertzel(160, 15.625, 500.0);
    im_Gbin[2] = new goertzel(160, 62.5, 500.0);
    im_Gbin[3] = new goertzel(160, 46.875, 500.0);
    for (int i = 0; i < 16; i++) syncbuf[i] = 0.0;
    rx_init();
}

Psk31Rx::~Psk31Rx()
{
    delete fir1; delete fir2;
    delete e0_filt; delete e1_filt; delete e2_filt; delete e3_filt;
    for (int i = 0; i < PSK_NUM_FILTERS; i++) { delete re_Gbin[i]; delete im_Gbin[i]; }
}

void Psk31Rx::rx_init()                              // fldigi psk::rx_init(), BPSK
{
    phaseacc = 0;
    prevsymbol = cmplx(1.0, 0.0);
    quality = cmplx(0.0, 0.0);
    shreg = 0;
    dcdshreg = 0;
    dcd = false;
    bitclk = 0;
    freqerr = 0.0;
    reset_filters = true;                            // (resetSN_IMD)
    afcmetric = 0.0;
    for (int i = 0; i < PSK_NUM_FILTERS; i++) { re_Gbin[i]->reset(); im_Gbin[i]->reset(); }
}

void Psk31Rx::rx_bit(int bit)                        // fldigi psk::rx_bit(), PSK varicode
{
    shreg = (shreg << 1) | !!bit;
    if ((shreg & 3) == 0) {                          // two zeros: the end of a character
        int c = psk_varicode_decode(shreg >> 2);
        if ((c != -1) && (dcd == true)) text += (char)c; // (fldigi: put_rx_char)
        shreg = 0;
    }
}

void Psk31Rx::phaseafc()                             // fldigi psk::phaseafc()
{
    if (afcmetric < 0.05) return;
    double error = (phase - bits * M_PI / 2.0);
    if (error < -M_PI / 2.0 || error > M_PI / 2.0) return;
    error *= samplerate / (TWOPI * symbollen);
    if (fabs(error) < sc_bw) {
        freqerr = error / dcdbits;
        frequency -= freqerr;                        // (fldigi: set_freq)
    }
    if (acquire) acquire--;
}

void Psk31Rx::afc()                                  // fldigi psk::afc()
{
    if (!afc_on) return;
    if (dcd == true || acquire) phaseafc();
}

void Psk31Rx::rx_symbol(cmplx symbol)                // fldigi psk::rx_symbol(), BPSK path
{
    int n = 2;
    double sigamp = norm(symbol);
    phase = arg(conj(prevsymbol) * symbol);          // the phase change since the last symbol
    prevsymbol = symbol;
    if (phase < 0) phase += TWOPI;
    bits = (((int)(phase / M_PI + 0.5)) & (n - 1)) << 1; // 0: no change (a 1), 2: reversal (a 0)
    averageamp = decayavg(averageamp, sigamp, SQLDECAY);

    // simple low pass filter for quality of signal
    double decay = SQLDECAY, attack = SQLDECAY;
    double cval = cos(n * phase), sval = sin(n * phase);
    quality = cmplx(decayavg(quality.real(), cval, cval > quality.real() ? attack : decay),
                    decayavg(quality.imag(), sval, sval > quality.real() ? attack : decay));
    metric = 100.0 * norm(quality);
    if (metric > 100) metric = 100;
    afcmetric = decayavg(afcmetric, norm(quality), 50);

    dcdshreg = (dcdshreg << 2) | bits;               // (symbits + 1 = 2 for BPSK)
    int set_dcd = -1;                                // 1 on, 0 off, -1 neither
    switch (dcdshreg) {
    case 0xAAAAAAAA:                                 // bpsk DCD ON: the idle preamble of reversals
        set_dcd = 1;
        break;
    case 0x00000000:                                 // bpsk DCD OFF: the postamble of steady carrier
        set_dcd = 0;
        break;
    default:
        if (squelch <= 0 || metric > squelch) dcd = true; // (fldigi: squelch slider)
        else dcd = false;
        dcdOFFcounter -= 1;
        if (dcdOFFcounter < 0) dcdOFFcounter = 0;
        break;
    }
    if (1 == set_dcd) { dcdOFFcounter = 0; dcd = true; acquire = 0; quality = cmplx(1.0, 0.0); }
    else if (0 == set_dcd) {                         // off only when seen 6 times in a row (no data loss mid-stream)
        if (++dcdOFFcounter > 5) { dcdOFFcounter = 0; dcd = false; acquire = 0; quality = cmplx(0.0, 0.0); }
    }
    if (dcd == true) rx_bit(!bits);                  // BPSK
}

void Psk31Rx::signalquality()                        // fldigi psk::signalquality()
{
    double r0 = re_Gbin[0]->mag(), r1 = re_Gbin[1]->mag(), r2 = re_Gbin[2]->mag(), r3 = re_Gbin[3]->mag();
    double i0 = im_Gbin[0]->mag(), i1 = im_Gbin[1]->mag(), i2 = im_Gbin[2]->mag(), i3 = im_Gbin[3]->mag();
    r0 = sqrtf(r0 * r0 + i0 * i0); r1 = sqrtf(r1 * r1 + i1 * i1);
    r2 = sqrtf(r2 * r2 + i2 * i2); r3 = sqrtf(r3 * r3 + i3 * i3);
    double e0 = e0_filt->run(r0), e1 = e1_filt->run(r1), e2 = e2_filt->run(r2), e3 = e3_filt->run(r3);
    if (e1 > e0) {
        if ((e1 > 2 * e2) && (e2 > 0)) { snratio = e1 / e2; if (snratio < 1.0) snratio = 1.0; }
        else snratio = 1.0;
    } else {
        if ((e0 > 2 * e2) && (e2 > 0)) { snratio = e0 / e2; if (snratio < 1.0) snratio = 1.0; }
        else snratio = 1.0;
    }
    if ((e1 > 2 * e3) && (e3 > 2 * e2)) { imdratio = e3 / e1; if (imdratio < (1.0 / snratio)) imdratio = 1.0 / snratio; }
    else imdratio = 1.0 / snratio;
}

void Psk31Rx::calcSN_IMD(cmplx z)                    // fldigi psk::calcSN_IMD(): energy at the base, fundamental, noise and 3rd order (500 Hz samples)
{
    if (reset_filters) {
        e0_filt->reset(); e1_filt->reset(); e2_filt->reset(); e3_filt->reset();
        for (int i = 0; i < PSK_NUM_FILTERS; i++) { re_Gbin[i]->reset(); im_Gbin[i]->reset(); }
        reset_filters = false;
    }
    bool isvalid = true;
    for (int i = 0; i < PSK_NUM_FILTERS; i++) { isvalid &= re_Gbin[i]->run(real(z)); isvalid &= im_Gbin[i]->run(imag(z)); }
    if (isvalid) {
        signalquality();
        for (int i = 0; i < PSK_NUM_FILTERS; i++) { re_Gbin[i]->reset(); im_Gbin[i]->reset(); }
    }
}

double Psk31Rx::get_snr_db() const { return 10.0 * log10(snratio); } // (fldigi shows s/n as 10 log10 of its ratio)
double Psk31Rx::get_imd_db() const { return 10.0 * log10(imdratio > 0 ? imdratio : 1e-6); }

void Psk31Rx::rx_process(const double *buf, int len) // fldigi psk::rx_process(), one carrier
{
    cmplx z, z2;
    bool can_rx_symbol = false;
    double delta = TWOPI * frequency / samplerate;   // the NCO step
    while (len-- > 0) {
        z = cmplx(*buf * cos(phaseacc), *buf * sin(phaseacc)); // mix with the internal NCO
        phaseacc += delta;
        if (phaseacc > TWOPI) phaseacc -= TWOPI;
        if (fir1->run(z, z)) {                       // filter and downsample to 16 samples a symbol (fir1 returns true when one is ready)
            fir2->run(z, z2);                        // final filter
            calcSN_IMD(z);
            // Symbol timing recovery: bitclk "draws" one symbol's magnitude waveform in syncbuf; the difference between
            // its halves says whether the clock is early or late (see fldigi psk.cxx for the full explanation).
            int idx = (int)bitclk;
            double sum = 0.0, ampsum = 0.0;
            syncbuf[idx] = 0.8 * syncbuf[idx] + 0.2 * abs(z2);
            double bitsteps = 16;                    // (symbollen >= 16)
            int symsteps = (int)(bitsteps / 2);
            for (int i = 0; i < symsteps; i++) { sum += (syncbuf[i] - syncbuf[i + symsteps]); ampsum += (syncbuf[i] + syncbuf[i + symsteps]); }
            sum = (ampsum == 0 ? 0 : sum / ampsum);  // correction as per PocketDigi
            bitclk -= sum / (5.0 * 16 / bitsteps);
            bitclk += 1;
            if (bitclk < 0) bitclk += bitsteps;
            if (bitclk >= bitsteps) { bitclk -= bitsteps; can_rx_symbol = true; afc(); } // a whole symbol: decide it
        }
        if (can_rx_symbol) { rx_symbol(z2); can_rx_symbol = false; delta = TWOPI * frequency / samplerate; } // (the AFC may have moved it)
        buf++;
    }
}

std::string Psk31Rx::take_text()
{
    std::string t; t.swap(text); return t;           // hand over, start empty
}

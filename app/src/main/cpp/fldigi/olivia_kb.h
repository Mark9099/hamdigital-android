// ----------------------------------------------------------------------------
// olivia_kb.h  --  Olivia receiver and transmitter: fldigi's olivia modem (src/olivia/olivia.cxx, fldigi 4.1.23) around
// Pawel Jalocha's MFSK engine (jalocha/pj_mfsk.h, unchanged), without fldigi's user interface and settings, for HF
// Digital Modes (Android). See ANDROID_CHANGES.txt.
//
// Copyright (C) 2006-2010
//		Dave Freese, W1HKJ
// MFSK transmitter and receiver: Pawel Jalocha, SP9VRC, 2004-2005
// (Android edition 2026, HF Digital Modes)
//
// Adapted from code contained in gmfsk source code distribution. Copyright (C) 2005 Tomi Manninen (oh2bns@sral.fi)
//
// This is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License
// as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later
// version. It is distributed WITHOUT ANY WARRANTY; see the GNU General Public License (LICENSE).
// ----------------------------------------------------------------------------
#pragma once
#include <cstdint>                                   // int16_t
#include <string>                                    // text
#include <vector>                                    // transmit audio

template <class Type> class MFSK_Receiver;           // (jalocha/pj_mfsk.h)

class OliviaRx {
public:
    OliviaRx(int tones = 8, int bandwidth = 250);    // 4..64 tones, 125..2000 Hz; 8 kHz audio
    ~OliviaRx();
    void set_freq(double f);                         // the signal's centre, audio Hz
    double get_freq() const { return frequency; }
    void set_squelch(double s);                      // 0..100 (0 = off): fldigi's slider -> the sync S/N threshold
    void rx_process(const double *buf, int len);     // audio in (8 kHz)
    std::string take_text();                         // the characters decoded since the last call
    double get_metric() const { return metric; }     // 0..100 (fldigi: 5 x (sync S/N - 3))
    double get_sync_snr() const { return snr; }      // the synchroniser's S/N (fldigi's Rx->SignalToNoiseRatio)
    double get_offset() const { return offset; }     // the signal's frequency offset found (Hz)
    void reset();                                    // start afresh (after a retune)

private:
    void preset();
    int unescape(int c);
    MFSK_Receiver<double> *Rx;
    int tones, bandwidth;
    double frequency = 1500, squelch = 0;
    int escape = 0;
    double metric = 0, snr = 0, offset = 0;
    std::string text;                                // decoded, not yet taken
};

/** Olivia transmit audio for a whole message (8 kHz): fldigi's start tones, the text through the MFSK transmitter, the
 *  stop tones; centred on f0 Hz; peak amplitude 0..1. */
std::vector<int16_t> olivia_tx_audio(const std::string &text, int tones, int bandwidth, double f0, double amplitude);

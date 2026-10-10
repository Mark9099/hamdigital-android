// ----------------------------------------------------------------------------
// olivia_kb.cxx  --  Olivia receiver and transmitter: fldigi's olivia modem (src/olivia/olivia.cxx, fldigi 4.1.23) around
// Pawel Jalocha's MFSK engine (jalocha/pj_mfsk.h, unchanged). Kept from fldigi: restart() (the receiver's and
// transmitter's parameters: tones, bandwidth, fldigi's default sync margin 8 and integration 4 FEC blocks, the first
// carrier from the centre frequency), rx_process (the squelch slider as the sync threshold, unescape of 8-bit
// characters, control codes dropped, the metric), send_tones (the start / stop tones at the band edges, with fldigi's
// raised-cosine shaping) and tx_process (the start tones, the text a character at a time with a leading NUL, Stop,
// the transmitter run out, the stop tones). Removed: the user interface, progdefaults / progStatus (now the
// constructor's tones / bandwidth, set_freq, set_squelch), the waterfall-based s/n, reverse. See ANDROID_CHANGES.txt.
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
#include "olivia_kb.h"                               // this file's API
#include <cmath>                                     // cos
#include "jalocha/pj_mfsk.h"                         // MFSK_Transmitter, MFSK_Receiver (Pawel Jalocha)

static const double SR = 8000;                       // fldigi's Olivia sample rate
static const int SMARGIN = 8, SINTEG = 4;            // fldigi's defaults: sync tune margin (tone spacings), integration (FEC blocks)
static const int SCBLOCKSIZE = 512;                  // (fldigi sound.h)
static const int TONE_DURATION = SCBLOCKSIZE * 16;   // the start / stop tones (fldigi olivia.h): 1.024 s
static const int SR4 = TONE_DURATION / 4;            // each tone's length

static double fc_offset(int bandwidth, int tones) { return bandwidth * (1.0 - 0.5 / tones) / 2.0; } // centre to first carrier (fldigi)

OliviaRx::OliviaRx(int t, int bw) : tones(t), bandwidth(bw)
{
    Rx = new MFSK_Receiver<double>;
    preset();
}

OliviaRx::~OliviaRx() { delete Rx; }

void OliviaRx::preset()                              // fldigi olivia::restart(), receive part
{
    Rx->Tones = tones;
    Rx->Bandwidth = bandwidth;
    Rx->SyncMargin = SMARGIN;
    Rx->SyncIntegLen = SINTEG;
    Rx->SyncThreshold = squelch > 0 ? std::min(std::max(squelch / 5.0 + 3.0, 0.0), 90.0) : 0.0; // (fldigi: the squelch slider)
    Rx->SampleRate = SR;
    Rx->InputSampleRate = SR;
    Rx->FirstCarrierMultiplier = (frequency - fc_offset(bandwidth, tones)) / 500.0; // (not reversed)
    Rx->Reverse = 0;
    Rx->Preset();
    metric = 0; snr = 0; offset = 0; escape = 0;
}

void OliviaRx::set_freq(double f) { if (f != frequency) { frequency = f; Rx->FirstCarrierMultiplier = (frequency - fc_offset(bandwidth, tones)) / 500.0; Rx->Preset(); } } // (fldigi: on a change of frequency)
void OliviaRx::set_squelch(double s) { squelch = s; Rx->SyncThreshold = s > 0 ? std::min(std::max(s / 5.0 + 3.0, 0.0), 90.0) : 0.0; }
void OliviaRx::reset() { Rx->Reset(); escape = 0; } // fldigi olivia::rx_init()

int OliviaRx::unescape(int c)                        // fldigi olivia::unescape() (8-bit characters on: fldigi's default)
{
    if (escape) { escape = 0; return c + 128; }
    if (c == 127) { escape = 1; return -1; }
    return c;
}

void OliviaRx::rx_process(const double *buf, int len) // fldigi olivia::rx_process()
{
    Rx->Process(const_cast<double *>(buf), len);
    uint8_t ch = 0; int c;
    while (Rx->GetChar(ch) > 0)
        if ((c = unescape(ch)) != -1 && c > 7) text += (char)c; // (fldigi: control codes below 8 dropped)
    offset = Rx->FrequencyOffset();
    snr = Rx->SignalToNoiseRatio();
    metric = std::min(std::max(5.0 * (snr - 3.0), 0.0), 100.0);
}

std::string OliviaRx::take_text() { std::string t; t.swap(text); return t; }

std::vector<int16_t> olivia_tx_audio(const std::string &text, int tones, int bandwidth, double f0, double amplitude)
{
    std::vector<int16_t> out;
    auto put = [&](const double *b, int n) { for (int i = 0; i < n; i++) { double v = b[i] * amplitude * 32767; out.push_back((int16_t)(v > 32767 ? 32767 : v < -32767 ? -32767 : v)); } };
    double ampshape[SR4];                            // fldigi's tone shaping: raised-cosine ends, 1/8 of the tone each
    for (int i = 0; i < SR4; i++) ampshape[i] = 1.0;
    for (int i = 0; i < SR4 / 8; i++) ampshape[i] = ampshape[SR4 - 1 - i] = 0.5 * (1.0 - cos(M_PI * i / (SR4 / 8)));
    auto send_tones = [&]() {                        // fldigi olivia::send_tones(): low edge, high edge, low, high
        std::vector<double> tb(TONE_DURATION); double ph = 0;
        double fa = f0 - bandwidth / 2.0, fb = f0 + bandwidth / 2.0;
        auto nco = [&](double f) { ph += 2.0 * M_PI * f / SR; if (ph > M_PI) ph -= 2.0 * M_PI; return cos(ph); };
        ph = 0; for (int i = 0; i < SR4; i++) tb[2 * SR4 + i] = tb[i] = nco(fa) * ampshape[i];
        ph = 0; for (int i = 0; i < SR4; i++) tb[3 * SR4 + i] = tb[SR4 + i] = nco(fb) * ampshape[i];
        put(tb.data(), TONE_DURATION);
    };
    MFSK_Transmitter<double> Tx;                     // fldigi olivia::restart(), transmit part
    Tx.Tones = tones; Tx.Bandwidth = bandwidth; Tx.SampleRate = SR; Tx.OutputSampleRate = SR;
    Tx.FirstCarrierMultiplier = (f0 - fc_offset(bandwidth, tones)) / 500.0; Tx.Reverse = 0;
    if (Tx.Preset() < 0) return out;
    std::vector<double> buf(Tx.MaxOutputLen);
    Tx.Start();                                      // (fldigi tx_init)
    send_tones();                                    // the start tones
    Tx.PutChar(0);                                   // (fldigi: the transmitter needs at least one character)
    size_t i = 0; bool stop = false;
    while (true) {                                   // fldigi olivia::tx_process(), until the transmitter has run out
        if (stop || Tx.GetReadReady() < Tx.BitsPerSymbol) {
            if (!stop && i >= text.size()) { stop = true; Tx.Stop(); }
            else if (!stop) { unsigned char c = (unsigned char)text[i++]; Tx.PutChar(c > 127 ? '.' : c); } // (8-bit characters as '.': plain text only)
        }
        int len = Tx.Output(buf.data());
        if (len > 0) put(buf.data(), len);
        if (!Tx.Running()) break;
    }
    send_tones();                                    // the stop tones
    std::vector<double> z(SCBLOCKSIZE, 0.0); put(z.data(), SCBLOCKSIZE); // and a block of silence (fldigi)
    return out;
}

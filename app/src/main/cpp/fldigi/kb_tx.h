// RTTY and PSK31 transmit audio (kb_tx.cxx, following fldigi's transmitters): a whole message as 8 kHz samples.
#pragma once
#include <cstdint>                                   // int16_t
#include <string>
#include <vector>

/** PSK31: preamble, the text in PSK varicode, postamble; carrier at f0 Hz; peak amplitude 0..1. */
std::vector<int16_t> psk31_tx_audio(const std::string &text, double f0, double amplitude);

/** RTTY: the text in Baudot (letters / figures), centre f0 Hz (mark f0 + shift/2), at baud; peak amplitude 0..1. */
std::vector<int16_t> rtty_tx_audio(const std::string &text, double f0, double shift, double baud, double amplitude);

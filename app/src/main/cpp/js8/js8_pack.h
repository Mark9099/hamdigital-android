// JS8 transmit (js8_pack.cpp): JS8Call's frame packing for heartbeats / CQs, directed messages and free text, and the
// JS8 Normal audio for a frame. Each message is one or more frames, sent in consecutive 15 s slots.
#pragma once
#include <cstdint>
#include <string>
#include <vector>

struct Js8TxFrame { std::string frame; int bits; };  // 12 characters, frame-type bits (first / last)

/** A heartbeat ("@HB HEARTBEAT GRID"), or with cq >= 0 a CQ ("@ALLCALL CQ..." - 0 "CQ CQ CQ", 1 "CQ DX", ..., 7 "CQ"). */
std::vector<Js8TxFrame> js8_heartbeat(std::string const &mycall, std::string const &grid, int cq);

/** A directed message to [to]: command number cmd (JS8Call's directed_cmds: 0 SNR?, 4 GRID?, 14 ACK, 25 SNR, 28 73,
 *  31 free text, ...), num for SNR (dB, else ""), and text after it sent in data frames. */
std::vector<Js8TxFrame> js8_directed(std::string const &mycall, std::string const &to, int cmd, std::string const &num, std::string const &text);

/** Free text to everyone: a directed message to @ALLCALL with the text in data frames (as JS8Call sends it). */
std::vector<Js8TxFrame> js8_text(std::string const &mycall, std::string const &text);

/** JS8 Normal audio for one frame: 79 symbols of continuous-phase 8-FSK from f0 Hz, 12 kHz, 12.64 s. */
std::vector<int16_t> js8_tx_audio(Js8TxFrame const &f, double f0, double amplitude);

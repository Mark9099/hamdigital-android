// JS8 transmit: JS8Call's frame packers (varicode.cpp: pack72bits, packAlphaNumeric50, packCallsign, packGrid,
// packCompoundFrame, the heartbeat / CQ, directed and Huffman data frames) ported from Qt to the C++ standard library,
// the frame sequence buildMessageFrames makes for these messages (first / last flags), and the audio as JS8Call's
// Modulator makes it (continuous-phase 8-FSK, 6.25 Hz spacing, 1920 samples a symbol for JS8 Normal). The free-form
// parsing of typed text with Qt regular expressions is replaced by the app choosing the kind of message. Data frames
// use JS8Call's Huffman coding (its other choice, JSC compression, is only an alternative; receivers decode both).
// (HF Digital Modes)
//
// This file is part of JS8Call. (C) 2018 Jordan Sherer <kn4crd@gmail.com> - All Rights Reserved. Distributed under the
// GNU General Public License v3 (LICENSE).
#include "js8_pack.h"                                // this file's API
#include "JS8.hpp"                                   // JS8::encode, Costas arrays
#include <cctype>                                    // toupper
#include <cmath>                                     // sin
#include <map>                                       // the tables

namespace
{
using Bits = std::vector<bool>;

const std::string alphabet72 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz-+/?.";
const std::string alphanumeric = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ /@";
const std::uint32_t nbasecall = 37u * 36 * 10 * 27 * 27 * 27;
const std::uint16_t nmaxgrid = (1 << 15) - 1;
const std::map<std::string, std::uint32_t> basecalls = { {"<....>", nbasecall + 1}, {"@ALLCALL", nbasecall + 2}, {"@HB", nbasecall + 45}, {"@CQ", nbasecall + 44} };
const std::map<std::string, std::string> hufftable = {
    {" ", "01"}, {"E", "100"}, {"T", "1101"}, {"A", "0011"}, {"O", "11111"}, {"I", "11100"}, {"N", "10111"},
    {"S", "10100"}, {"H", "00011"}, {"R", "00000"}, {"D", "111011"}, {"L", "110011"}, {"C", "110001"}, {"U", "101101"},
    {"M", "101011"}, {"W", "001011"}, {"F", "001001"}, {"G", "000101"}, {"Y", "000011"}, {"P", "1111011"},
    {"B", "1111001"}, {".", "1110100"}, {"V", "1100101"}, {"K", "1100100"}, {"-", "1100001"}, {"+", "1100000"},
    {"?", "1011001"}, {"!", "1011000"}, {"\"", "1010101"}, {"X", "1010100"}, {"0", "0010101"}, {"J", "0010100"},
    {"1", "0010001"}, {"Q", "0010000"}, {"2", "0001001"}, {"Z", "0001000"}, {"3", "0000101"}, {"5", "0000100"},
    {"4", "11110101"}, {"9", "11110100"}, {"8", "11110001"}, {"6", "11110000"}, {"7", "11101011"}, {"/", "11101010"},
};
enum { FrameHeartbeat = 0, FrameCompound = 1, FrameDirected = 3 };
enum { JS8Call = 0, JS8CallFirst = 1, JS8CallLast = 2 };

Bits intToBits(std::uint64_t value, int expected)
{
    Bits b; while (value) { b.insert(b.begin(), (bool)(value & 1)); value >>= 1; }
    while ((int)b.size() < expected) b.insert(b.begin(), false);
    return b;
}
std::uint64_t bitsToInt(Bits const &v, size_t start, size_t n)
{
    std::uint64_t r = 0; for (size_t i = start; i < start + n && i < v.size(); i++) r = (r << 1) + (v[i] ? 1 : 0); return r;
}
void append(Bits &a, Bits const &b) { a.insert(a.end(), b.begin(), b.end()); }

std::string pack72bits(std::uint64_t value, std::uint8_t rem) // Varicode::pack72bits
{
    char packed[12];
    std::uint8_t remHigh = (std::uint8_t)(((value & 0xF) << 2) | (rem >> 6));
    std::uint8_t remLow = rem & 0x3F;
    value >>= 4;
    packed[11] = alphabet72[remLow];
    packed[10] = alphabet72[remHigh];
    for (int i = 0; i < 10; i++) { packed[9 - i] = alphabet72[value & 0x3F]; value >>= 6; }
    return std::string(packed, 12);
}
int an(char c) { auto p = alphanumeric.find(c); return p == std::string::npos ? -1 : (int)p; }

std::uint64_t packAlphaNumeric50(std::string word)  // Varicode::packAlphaNumeric50
{
    std::string w; for (char c : word) if (an(c) >= 0 && c != ' ') w += c; else if (c == ' ') w += c; // [A-Z0-9 /@]
    if (w.size() > 3 && w[3] != '/') w.insert(3, " ");
    if (w.size() > 7 && w[7] != '/') w.insert(7, " ");
    while (w.size() < 11) w += ' ';
    std::uint64_t const k = 38ull * 38 * 38;         // (the mixed-radix places, as the original)
    return (std::uint64_t)k * 2 * k * 2 * 38 * 38 * an(w[0]) + (std::uint64_t)k * 2 * k * 2 * 38 * an(w[1]) + (std::uint64_t)k * 2 * k * 2 * an(w[2]) +
           (std::uint64_t)k * 2 * k * (w[3] == '/') + (std::uint64_t)k * 2 * 38 * 38 * an(w[4]) + (std::uint64_t)k * 2 * 38 * an(w[5]) +
           (std::uint64_t)k * 2 * an(w[6]) + (std::uint64_t)k * (w[7] == '/') + 38ull * 38 * an(w[8]) + 38ull * an(w[9]) + an(w[10]);
}

std::uint32_t packCallsign(std::string call, bool *portable) // Varicode::packCallsign
{
    for (auto &c : call) c = (char)toupper((unsigned char)c);
    auto bc = basecalls.find(call); if (bc != basecalls.end()) return bc->second;
    if (call.size() > 2 && call.compare(call.size() - 2, 2, "/P") == 0) { call = call.substr(0, call.size() - 2); if (portable) *portable = true; }
    if (call.rfind("3DA0", 0) == 0) call = "3D0" + call.substr(4);
    if (call.rfind("3X", 0) == 0 && call.size() > 2 && call[2] >= 'A' && call[2] <= 'Z') call = "Q" + call.substr(2);
    size_t len = call.size(); if (len < 2 || len > 6) return 0;
    std::vector<std::string> perms = { call };     // the same permutations as the original
    if (len == 2) perms.push_back(" " + call + "   ");
    if (len == 3) { perms.push_back(" " + call + "  "); perms.push_back(call + "   "); }
    if (len == 4) { perms.push_back(" " + call + " "); perms.push_back(call + "  "); }
    if (len == 5) { perms.push_back(" " + call); perms.push_back(call + " "); }
    auto isd = [](char c) { return c >= '0' && c <= '9'; }; auto isu = [](char c) { return c >= 'A' && c <= 'Z'; };
    std::string matched;                             // pack_callsign_pattern: ([0-9A-Z ])([0-9A-Z])([0-9])([A-Z ])([A-Z ])([A-Z ]); the last match wins
    for (auto const &p : perms) {
        if (p.size() < 6) continue;
        for (size_t o = 0; o + 6 <= p.size(); o++) { // (QRegularExpression::match finds it anywhere)
            char const *q = p.c_str() + o;
            if ((isd(q[0]) || isu(q[0]) || q[0] == ' ') && (isd(q[1]) || isu(q[1])) && isd(q[2]) && (isu(q[3]) || q[3] == ' ') && (isu(q[4]) || q[4] == ' ') && (isu(q[5]) || q[5] == ' '))
            { matched = std::string(q, 6); break; }
        }
    }
    if (matched.size() < 6) return 0;
    std::uint32_t packed = an(matched[0]);
    packed = 36 * packed + an(matched[1]);
    packed = 10 * packed + an(matched[2]);
    packed = 27 * packed + an(matched[3]) - 10;
    packed = 27 * packed + an(matched[4]) - 10;
    packed = 27 * packed + an(matched[5]) - 10;
    return packed;
}

std::uint16_t packGrid(std::string g)               // Varicode::packGrid (4 characters)
{
    if (g.size() < 4) return nmaxgrid;
    g = g.substr(0, 4); for (auto &c : g) c = (char)toupper((unsigned char)c);
    std::string gg = g + "mm";                       // grid2deg: the subsquare's middle
    int nlong = 180 - 20 * (gg[0] - 'A'); int n20d = 2 * (gg[2] - '0'); float xminlong = 5 * (gg[4] - 'a' + 0.5f);
    float dlong = nlong - n20d - xminlong / 60.0f;
    int nlat = -90 + 10 * (gg[1] - 'A') + gg[3] - '0'; float xminlat = 2.5f * (gg[5] - 'a' + 0.5f);
    float dlat = nlat + xminlat / 60.0f;
    int ilong = (int)dlong, ilat = (int)(dlat + 90);
    return (std::uint16_t)(((ilong + 180) / 2) * 180 + ilat);
}

std::string packCompoundFrame(std::string const &call, std::uint8_t type, std::uint16_t num, std::uint8_t bits3) // Varicode::packCompoundFrame
{
    std::uint64_t pc = packAlphaNumeric50(call); if (pc == 0) return std::string();
    std::uint16_t packed_11 = (num & (((1 << 11) - 1) << 5)) >> 5;
    std::uint8_t packed_5 = num & 0x1F;
    std::uint8_t packed_8 = (std::uint8_t)((packed_5 << 3) | bits3);
    Bits b = intToBits(type, 3); append(b, intToBits(pc, 50)); append(b, intToBits(packed_11, 11)); // [3][50][11],[5][3]
    return pack72bits(bitsToInt(b, 0, 64), packed_8);
}

std::string packDirected(std::string const &from, std::string const &to, int cmd, std::string const &num) // packDirectedMessage, structured
{
    bool pf = false, pt = false;
    std::uint32_t f = packCallsign(from, &pf), t = packCallsign(to, &pt);
    if (!f || !t) return std::string();
    std::uint8_t inum = 0;
    if (!num.empty()) { int v = std::atoi(num.c_str()); v = v < -30 ? -30 : (v > 31 ? 31 : v); inum = (std::uint8_t)(v + 30 + 1); } // packNum
    std::uint8_t extra = (std::uint8_t)((((int)pf) << 7) + (((int)pt) << 6) + inum);
    Bits b = intToBits(FrameDirected, 3); append(b, intToBits(f, 28)); append(b, intToBits(t, 28)); append(b, intToBits(cmd % 32, 5)); // [3][28][28][5],[2][6]
    return pack72bits(bitsToInt(b, 0, 64), extra);
}

std::string packHuffData(std::string const &text, int *n) // packHuffMessage with the data prefix [1][0]
{
    Bits fb = {true, false}; int i = 0;              // data frame, not compressed
    for (char c : text) { std::string k(1, c); if (!hufftable.count(k)) { *n = 0; return std::string(); } } // only valid characters
    for (char c : text) {                            // huffEncode, while it fits
        auto const &code = hufftable.at(std::string(1, c));
        if (fb.size() + code.size() < 72) { for (char b : code) fb.push_back(b == '1'); i++; continue; }
        break;
    }
    int pad = 72 - (int)fb.size();                   // pad: a 0, then 1s
    for (int k = 0; k < pad; k++) fb.push_back(k != 0);
    *n = i;
    return pack72bits(bitsToInt(fb, 0, 64), (std::uint8_t)bitsToInt(fb, 64, 8));
}

std::string upper(std::string s) { for (auto &c : s) c = (char)toupper((unsigned char)c); return s; }
void flags(std::vector<Js8TxFrame> &v) { if (!v.empty()) { v.front().bits |= JS8CallFirst; v.back().bits |= JS8CallLast; } } // first / last of the message
}

std::vector<Js8TxFrame> js8_heartbeat(std::string const &mycall, std::string const &grid, int cq)
{
    std::uint16_t extra = grid.size() >= 4 ? packGrid(grid) : nmaxgrid;
    std::uint8_t number = 0;                         // hbs.key("HB") = 0
    if (cq >= 0) { extra |= (1 << 15); number = (std::uint8_t)(cq & 7); } // CQ: the alternative form, cqs[number]
    std::vector<Js8TxFrame> v; std::string f = packCompoundFrame(upper(mycall), FrameHeartbeat, extra, number);
    if (!f.empty()) v.push_back({f, JS8Call});
    flags(v); return v;
}

std::vector<Js8TxFrame> js8_directed(std::string const &mycall, std::string const &to, int cmd, std::string const &num, std::string const &text)
{
    std::vector<Js8TxFrame> v;
    std::string t = upper(text);
    std::string d = packDirected(upper(mycall), upper(to), t.empty() ? cmd : (cmd == 31 ? 31 : cmd), num);
    if (d.empty()) return v;
    v.push_back({d, JS8Call});
    while (!t.empty()) {                             // the text after it, in data frames
        int n = 0; std::string f = packHuffData(t, &n);
        if (n == 0) break;                           // a character Huffman cannot send
        v.push_back({f, JS8Call}); t = t.substr(n);
    }
    flags(v); return v;
}

std::vector<Js8TxFrame> js8_text(std::string const &mycall, std::string const &text)
{
    return js8_directed(mycall, "@ALLCALL", 31, "", text); // "MYCALL: @ALLCALL TEXT" - JS8Call's text to everyone
}

std::vector<int16_t> js8_tx_audio(Js8TxFrame const &f, double f0, double amplitude)
{
    int tones[JS8_NUM_SYMBOLS];
    JS8::encode(f.bits, JS8::Costas::array(JS8::Costas::Type::ORIGINAL), f.frame.c_str(), tones); // JS8 Normal: the original Costas arrays
    const int sps = JS8A_SYMBOL_SAMPLES;            // 1920
    const double spacing = 12000.0 / sps;           // 6.25 Hz
    std::vector<int16_t> out; out.reserve(JS8_NUM_SYMBOLS * sps);
    double phi = 0;
    for (int s = 0; s < JS8_NUM_SYMBOLS; s++) {      // continuous phase, as JS8Call's Modulator
        double dphi = 2 * M_PI * (f0 + tones[s] * spacing) / 12000.0;
        for (int i = 0; i < sps; i++) { out.push_back((int16_t)(sin(phi) * amplitude * 32767)); phi += dphi; if (phi > 2 * M_PI) phi -= 2 * M_PI; }
    }
    int r = 120;                                     // 10 ms ramps
    for (int i = 0; i < r; i++) { double e = 0.5 - 0.5 * cos(M_PI * i / r); out[i] = (int16_t)(out[i] * e); out[out.size() - 1 - i] = (int16_t)(out[out.size() - 1 - i] * e); }
    return out;
}

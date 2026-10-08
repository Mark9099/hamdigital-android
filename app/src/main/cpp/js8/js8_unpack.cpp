// JS8 message unpacking (see js8_unpack.h): JS8Call's receive-side Varicode, JSC and DecodedText functions, ported
// from Qt (QString, QMap, QVector<bool>) to the C++ standard library line for line. Where Qt's behaviour mattered it is
// kept: QMap iterates keys in sorted order and QMap::key(value) returns the first such key (std::map does both the
// same way for these ASCII keys); huffDecode's scan through the table carries on after a match, as the original does.
// (HF Digital Modes)
//
// This file is part of JS8Call. (C) 2018 Jordan Sherer <kn4crd@gmail.com> - All Rights Reserved. Distributed under the
// GNU General Public License v3 (LICENSE).
#include "js8_unpack.h"                              // this file's API
#include "jsc.h"                                     // JSC::map (decompression)
#include <cstdint>                                   // fixed-width integers
#include <map>                                       // QMap stand-in (sorted)
#include <vector>                                    // QVector<bool>, QStringList stand-ins

namespace js8unpack
{
using Bits = std::vector<bool>;                      // QVector<bool>
using Strings = std::vector<std::string>;            // QStringList

// ---- varicode.cpp tables ----
static const std::string alphabet72 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz-+/?.";
static const std::string alphanumeric = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ /@"; // callsign and grid alphabet

static const std::map<std::string, int> directed_cmds = {
    {" HEARTBEAT", -1}, {" HB", -1}, {" CQ", -1}, {" SNR?", 0}, {"?", 0}, {" DIT DIT", 1}, {" HEARING?", 3}, {" GRID?", 4},
    {">", 5}, {" STATUS?", 6}, {" STATUS", 7}, {" HEARING", 8}, {" MSG", 9}, {" MSG TO:", 10}, {" QUERY", 11},
    {" QUERY MSGS", 12}, {" QUERY MSGS?", 12}, {" QUERY CALL", 13}, {" GRID", 15}, {" INFO?", 16}, {" INFO", 17},
    {" FB", 18}, {" HW CPY?", 19}, {" SK", 20}, {" RR", 21}, {" QSL?", 22}, {" QSL", 23}, {" CMD", 24}, {" SNR", 25},
    {" NO", 26}, {" YES", 27}, {" 73", 28}, {" NACK", 2}, {" ACK", 14}, {" HEARTBEAT SNR", 29}, {" AGN?", 30},
    {"  ", 31}, {" ", 31},
};
static const int snr_cmds[] = {25, 29};

static const std::map<std::string, std::string> hufftable = {
    {" ", "01"}, {"E", "100"}, {"T", "1101"}, {"A", "0011"}, {"O", "11111"}, {"I", "11100"}, {"N", "10111"},
    {"S", "10100"}, {"H", "00011"}, {"R", "00000"}, {"D", "111011"}, {"L", "110011"}, {"C", "110001"}, {"U", "101101"},
    {"M", "101011"}, {"W", "001011"}, {"F", "001001"}, {"G", "000101"}, {"Y", "000011"}, {"P", "1111011"},
    {"B", "1111001"}, {".", "1110100"}, {"V", "1100101"}, {"K", "1100100"}, {"-", "1100001"}, {"+", "1100000"},
    {"?", "1011001"}, {"!", "1011000"}, {"\"", "1010101"}, {"X", "1010100"}, {"0", "0010101"}, {"J", "0010100"},
    {"1", "0010001"}, {"Q", "0010000"}, {"2", "0001001"}, {"Z", "0001000"}, {"3", "0000101"}, {"5", "0000100"},
    {"4", "11110101"}, {"9", "11110100"}, {"8", "11110001"}, {"6", "11110000"}, {"7", "11101011"}, {"/", "11101010"},
};

static const std::uint32_t nbasecall = 37u * 36 * 10 * 27 * 27 * 27;
static const std::uint16_t nbasegrid = 180 * 180;
static const std::uint16_t nusergrid = nbasegrid + 10;
static const std::uint16_t nmaxgrid = (1 << 15) - 1;

static const std::map<std::string, std::uint32_t> basecalls = {
    {"<....>", nbasecall + 1}, {"@ALLCALL", nbasecall + 2}, {"@JS8NET", nbasecall + 3}, {"@DX/NA", nbasecall + 4},
    {"@DX/SA", nbasecall + 5}, {"@DX/EU", nbasecall + 6}, {"@DX/AS", nbasecall + 7}, {"@DX/AF", nbasecall + 8},
    {"@DX/OC", nbasecall + 9}, {"@DX/AN", nbasecall + 10}, {"@REGION/1", nbasecall + 11}, {"@REGION/2", nbasecall + 12},
    {"@REGION/3", nbasecall + 13}, {"@GROUP/0", nbasecall + 14}, {"@GROUP/1", nbasecall + 15}, {"@GROUP/2", nbasecall + 16},
    {"@GROUP/3", nbasecall + 17}, {"@GROUP/4", nbasecall + 18}, {"@GROUP/5", nbasecall + 19}, {"@GROUP/6", nbasecall + 20},
    {"@GROUP/7", nbasecall + 21}, {"@GROUP/8", nbasecall + 22}, {"@GROUP/9", nbasecall + 23}, {"@COMMAND", nbasecall + 24},
    {"@CONTROL", nbasecall + 25}, {"@NET", nbasecall + 26}, {"@NTS", nbasecall + 27}, {"@RESERVE/0", nbasecall + 28},
    {"@RESERVE/1", nbasecall + 29}, {"@RESERVE/2", nbasecall + 30}, {"@RESERVE/3", nbasecall + 31}, {"@RESERVE/4", nbasecall + 32},
    {"@APRSIS", nbasecall + 33}, {"@RAGCHEW", nbasecall + 34}, {"@JS8", nbasecall + 35}, {"@EMCOMM", nbasecall + 36},
    {"@ARES", nbasecall + 37}, {"@MARS", nbasecall + 38}, {"@AMRRON", nbasecall + 39}, {"@RACES", nbasecall + 40},
    {"@RAYNET", nbasecall + 41}, {"@RADAR", nbasecall + 42}, {"@SKYWARN", nbasecall + 43}, {"@CQ", nbasecall + 44},
    {"@HB", nbasecall + 45}, {"@QSO", nbasecall + 46}, {"@QSOPARTY", nbasecall + 47}, {"@CONTEST", nbasecall + 48},
    {"@FIELDDAY", nbasecall + 49}, {"@SOTA", nbasecall + 50}, {"@IOTA", nbasecall + 51}, {"@POTA", nbasecall + 52},
    {"@QRP", nbasecall + 53}, {"@QRO", nbasecall + 54},
};
static const char *cqs[8] = {"CQ CQ CQ", "CQ DX", "CQ QRP", "CQ CONTEST", "CQ FIELD", "CQ FD", "CQ CQ", "CQ"};

// ---- small helpers (Qt's QString functions) ----
static std::string trimmed(std::string const &s)     // QString::trimmed
{
    size_t a = s.find_first_not_of(" \t\r\n"), b = s.find_last_not_of(" \t\r\n");
    return a == std::string::npos ? std::string() : s.substr(a, b - a + 1);
}
static std::string join(Strings const &v, std::string const &sep, size_t from = 0) // QStringList::mid(from).join(sep)
{
    std::string r; for (size_t i = from; i < v.size(); i++) { if (i > from) r += sep; r += v[i]; } return r;
}
static std::string directedKey(int value)           // QMap::key(value): the first key, in sorted order, for value
{
    for (auto const &kv : directed_cmds) if (kv.second == value) return kv.first;
    return std::string();
}
static bool isSNRCommand(std::string const &cmd)
{
    auto it = directed_cmds.find(cmd); if (it == directed_cmds.end()) return false;
    for (int c : snr_cmds) if (c == it->second) return true;
    return false;
}
static std::string formatSNR(int snr)               // "+05", "-12"
{
    if (snr < -60 || snr > 60) return std::string();
    char b[8]; snprintf(b, sizeof b, snr >= 0 ? "+%02d" : "%03d", snr); // (QString("%1%2").arg(sign).arg(snr, width, 10, '0'))
    return b;
}

// ---- bits (Varicode) ----
static Bits intToBits(std::uint64_t value, int expected)
{
    Bits bits;
    while (value) { bits.insert(bits.begin(), (bool)(value & 1)); value >>= 1; }
    if (expected) while ((int)bits.size() < expected) bits.insert(bits.begin(), false);
    return bits;
}
static std::uint64_t bitsToInt(Bits const &v, size_t start, size_t n) // bitsToInt(bits.mid(start, n))
{
    std::uint64_t r = 0;
    for (size_t i = start; i < start + n && i < v.size(); i++) r = (r << 1) + (v[i] ? 1 : 0);
    return r;
}
static Bits mid(Bits const &v, long pos, long len = -1) // QVector::mid (a negative length: to the end)
{
    if (pos < 0) pos = 0;
    if (pos > (long)v.size()) return Bits();
    if (len < 0 || pos + len > (long)v.size()) len = (long)v.size() - pos;
    return Bits(v.begin() + pos, v.begin() + pos + len);
}
static long lastIndexOf0(Bits const &v)             // QVector::lastIndexOf(0)
{
    for (long i = (long)v.size() - 1; i >= 0; i--) if (!v[i]) return i;
    return -1;
}
static bool unpack72bits(std::string const &text, std::uint64_t &value, std::uint8_t &rem)
{
    value = 0; rem = 0;
    for (int i = 0; i < 12; i++) if (alphabet72.find(text[i]) == std::string::npos) return false; // not a JS8 frame
    for (int i = 0; i < 10; i++) value |= (std::uint64_t)(alphabet72.find(text[i])) << (58 - 6 * i);
    std::uint8_t remHigh = (std::uint8_t)alphabet72.find(text[10]);
    value |= remHigh >> 2;
    std::uint8_t remLow = (std::uint8_t)alphabet72.find(text[11]);
    rem = (std::uint8_t)(((remHigh & 3) << 6) | remLow);
    return true;
}
static char an(std::uint64_t i) { return i < alphanumeric.size() ? alphanumeric[i] : ' '; } // alphanumeric.at(i) (guarded)

static std::string unpackAlphaNumeric50(std::uint64_t packed)
{
    char w[11];
    w[10] = an(packed % 38); packed /= 38;
    w[9] = an(packed % 38); packed /= 38;
    w[8] = an(packed % 38); packed /= 38;
    w[7] = (packed % 2) ? '/' : ' '; packed /= 2;
    w[6] = an(packed % 38); packed /= 38;
    w[5] = an(packed % 38); packed /= 38;
    w[4] = an(packed % 38); packed /= 38;
    w[3] = (packed % 2) ? '/' : ' '; packed /= 2;
    w[2] = an(packed % 38); packed /= 38;
    w[1] = an(packed % 38); packed /= 38;
    w[0] = an(packed % 39);
    std::string r; for (char c : w) if (c != ' ') r += c; // value.replace(" ", "")
    return r;
}

static std::string unpackCallsign(std::uint32_t value, bool portable)
{
    for (auto const &kv : basecalls) if (kv.second == value) return kv.first; // a group or <....>
    char w[6];
    std::uint32_t tmp = value % 27 + 10; w[5] = an(tmp); value /= 27;
    tmp = value % 27 + 10; w[4] = an(tmp); value /= 27;
    tmp = value % 27 + 10; w[3] = an(tmp); value /= 27;
    tmp = value % 10; w[2] = an(tmp); value /= 10;
    tmp = value % 36; w[1] = an(tmp); value /= 36;
    w[0] = an(value);
    std::string call(w, 6);
    if (call.rfind("3D0", 0) == 0) call = "3DA0" + call.substr(3); // swaziland
    if (call[0] == 'Q' && call.size() > 1 && call[1] >= 'A' && call[1] <= 'Z') call = "3X" + call.substr(1); // guinea
    if (portable) call = trimmed(call) + "/P";
    return trimmed(call);
}

static std::string deg2grid(float dlong, float dlat)
{
    char g[6];
    if (dlong < -180) dlong += 360;
    if (dlong > 180) dlong -= 360;
    int nlong = int(60.0 * (180.0 - dlong) / 5);
    int n1 = nlong / 240, n2 = (nlong - 240 * n1) / 24, n3 = (nlong - 240 * n1 - 24 * n2);
    g[0] = (char)('A' + n1); g[2] = (char)('0' + n2); g[4] = (char)('a' + n3);
    int nlat = int(60.0 * (dlat + 90) / 2.5);
    n1 = nlat / 240; n2 = (nlat - 240 * n1) / 24; n3 = (nlat - 240 * n1 - 24 * n2);
    g[1] = (char)('A' + n1); g[3] = (char)('0' + n2); g[5] = (char)('a' + n3);
    return std::string(g, 6);
}

static std::string unpackGrid(std::uint16_t value)
{
    if (value > nbasegrid) return std::string();
    float dlat = value % 180 - 90;
    float dlong = value / 180 * 2 - 180 + 2;
    return deg2grid(dlong, dlat).substr(0, 4);
}

static int unpackCmd(std::uint8_t value, std::uint8_t *pNum)
{
    if (value & (1 << 7)) {
        if (pNum) *pNum = value & ((1 << 6) - 1);
        return (value & (1 << 6)) ? directed_cmds.at(" HEARTBEAT SNR") : directed_cmds.at(" SNR");
    }
    if (pNum) *pNum = 0;
    return value & ((1 << 7) - 1);
}

static Strings unpackCompoundFrame(std::string const &text, std::uint8_t *pType, std::uint16_t *pNum, std::uint8_t *pBits3)
{
    Strings unpacked;
    if (text.size() < 12 || text.find(' ') != std::string::npos) return unpacked;
    std::uint64_t value; std::uint8_t packed_8;      // [3][50][11],[5][3] = 72
    if (!unpack72bits(text, value, packed_8)) return unpacked;
    Bits bits = intToBits(value, 64);
    std::uint8_t packed_5 = packed_8 >> 3, packed_3 = packed_8 & 7;
    std::uint8_t packed_flag = (std::uint8_t)bitsToInt(bits, 0, 3);
    if (packed_flag == FrameData || packed_flag == FrameDirected) return unpacked; // needs to be a ping type
    std::uint64_t packed_callsign = bitsToInt(bits, 3, 50);
    std::uint16_t packed_11 = (std::uint16_t)bitsToInt(bits, 53, 11);
    std::string callsign = unpackAlphaNumeric50(packed_callsign);
    std::uint16_t num = (std::uint16_t)((packed_11 << 5) | packed_5);
    if (pType) *pType = packed_flag;
    if (pNum) *pNum = num;
    if (pBits3) *pBits3 = packed_3;
    unpacked.push_back(callsign);
    unpacked.push_back("");
    return unpacked;
}

static Strings unpackHeartbeatMessage(std::string const &text, std::uint8_t *pType, bool *isAlt, std::uint8_t *pBits3)
{
    std::uint8_t type = FrameHeartbeat; std::uint16_t num = nmaxgrid; std::uint8_t bits3 = 0;
    Strings unpacked = unpackCompoundFrame(text, &type, &num, &bits3);
    if (unpacked.empty() || type != FrameHeartbeat) return Strings();
    unpacked.push_back(unpackGrid(num & ((1 << 15) - 1)));
    if (isAlt) *isAlt = (num & (1 << 15));
    if (pType) *pType = type;
    if (pBits3) *pBits3 = bits3;
    return unpacked;
}

static Strings unpackCompoundMessage(std::string const &text, std::uint8_t *pType, std::uint8_t *pBits3)
{
    std::uint8_t type = FrameCompound; std::uint16_t extra = nmaxgrid; std::uint8_t bits3 = 0;
    Strings unpacked = unpackCompoundFrame(text, &type, &extra, &bits3);
    if (unpacked.empty() || (type != FrameCompound && type != FrameCompoundDirected)) return Strings();
    if (extra <= nbasegrid) unpacked.push_back(" " + unpackGrid(extra));
    else if (nusergrid <= extra && extra < nmaxgrid) { // above the user grids: an SNR command
        std::uint8_t num = 0;
        int cmd = unpackCmd((std::uint8_t)(extra - nusergrid), &num);
        std::string cmdStr = directedKey(cmd);
        unpacked.push_back(cmdStr);
        if (isSNRCommand(cmdStr)) unpacked.push_back(formatSNR(num - 31));
    }
    if (pType) *pType = type;
    if (pBits3) *pBits3 = bits3;
    return unpacked;
}

static Strings unpackDirectedMessage(std::string const &text, std::uint8_t *pType)
{
    Strings unpacked;
    if (text.size() < 12 || text.find(' ') != std::string::npos) return unpacked;
    std::uint64_t value; std::uint8_t extra;          // [3][28][22][11],[2][6] = 72
    if (!unpack72bits(text, value, extra)) return unpacked;
    Bits bits = intToBits(value, 64);
    std::uint8_t packed_flag = (std::uint8_t)bitsToInt(bits, 0, 3);
    if (packed_flag != FrameDirected) return unpacked;
    std::uint32_t packed_from = (std::uint32_t)bitsToInt(bits, 3, 28);
    std::uint32_t packed_to = (std::uint32_t)bitsToInt(bits, 31, 28);
    std::uint8_t packed_cmd = (std::uint8_t)bitsToInt(bits, 59, 5);
    bool portable_from = ((extra >> 7) & 1) == 1, portable_to = ((extra >> 6) & 1) == 1;
    extra = extra % 64;
    std::string from = unpackCallsign(packed_from, portable_from);
    std::string to = unpackCallsign(packed_to, portable_to);
    std::string cmd = directedKey(packed_cmd % 32);
    unpacked.push_back(from); unpacked.push_back(to); unpacked.push_back(cmd);
    if (extra != 0) {
        if (isSNRCommand(cmd)) unpacked.push_back(formatSNR((int)extra - 31));
        else unpacked.push_back(std::to_string((int)extra - 31));
    }
    if (pType) *pType = packed_flag;
    return unpacked;
}

static std::string huffDecode(Bits const &bitvec)   // Varicode::huffDecode with the default table, as the original scans it
{
    std::string text, bits;
    for (bool b : bitvec) bits += b ? '1' : '0';
    while (!bits.empty()) {
        bool found = false;
        for (auto const &kv : hufftable) {           // (sorted keys; carries on through the table after a match)
            if (bits.compare(0, kv.second.size(), kv.second) == 0) {
                text += kv.first;
                bits = bits.substr(kv.second.size());
                found = true;
            }
        }
        if (!found) break;
    }
    return text;
}

static std::string jscDecompress(Bits const &bitvec) // JSC::decompress
{
    const std::uint32_t s = 7, c = 16 - s;           // b = 4
    std::uint32_t base[8];
    base[0] = 0; base[1] = s; base[2] = base[1] + s * c; base[3] = base[2] + s * c * c; base[4] = base[3] + s * c * c * c;
    base[5] = base[4] + s * c * c * c * c; base[6] = base[5] + s * c * c * c * c * c; base[7] = base[6] + s * c * c * c * c * c * c;
    std::vector<std::uint64_t> bytes; std::vector<std::uint32_t> separators;
    size_t i = 0, count = bitvec.size();
    while (i < count) {
        Bits b = mid(bitvec, (long)i, 4);
        if (b.size() != 4) break;
        std::uint64_t byte = bitsToInt(b, 0, 4);
        bytes.push_back(byte);
        i += 4;
        if (byte < s) {
            if (count > i && bitvec[i]) separators.push_back((std::uint32_t)bytes.size() - 1);
            i += 1;
        }
    }
    std::string out;
    std::uint32_t start = 0;
    while (start < bytes.size()) {
        std::uint32_t k = 0, j = 0;
        while (start + k < bytes.size() && bytes[start + k] >= s) { j = j * c + (std::uint32_t)(bytes[start + k] - s); k++; }
        if (j >= JSC::size) break;
        if (start + k >= bytes.size()) break;
        j = j * s + (std::uint32_t)bytes[start + k] + base[k];
        if (j >= JSC::size) break;
        out += JSC::map[j].str;                      // (Latin-1)
        if (!separators.empty() && separators.front() == start + k) { out += " "; separators.erase(separators.begin()); }
        start = start + (k + 1);
    }
    return out;
}

static std::string unpackDataMessage(std::string const &text)
{
    if (text.size() < 12 || text.find(' ') != std::string::npos) return std::string();
    std::uint64_t value; std::uint8_t rem;
    if (!unpack72bits(text, value, rem)) return std::string();
    Bits bits = intToBits(value, 64); Bits r = intToBits(rem, 8); bits.insert(bits.end(), r.begin(), r.end());
    if (!bits[0]) return std::string();              // not data
    bits = mid(bits, 1);
    bool compressed = bits[0];
    long n = lastIndexOf0(bits);
    bits = mid(bits, 1, n - 1);                      // trim off the pad bits
    return compressed ? jscDecompress(bits) : huffDecode(bits);
}

static std::string unpackFastDataMessage(std::string const &text)
{
    if (text.size() < 12 || text.find(' ') != std::string::npos) return std::string();
    std::uint64_t value; std::uint8_t rem;
    if (!unpack72bits(text, value, rem)) return std::string();
    Bits bits = intToBits(value, 64); Bits r = intToBits(rem, 8); bits.insert(bits.end(), r.begin(), r.end());
    long n = lastIndexOf0(bits);
    bits = mid(bits, 0, n);                          // trim off the pad bits
    return jscDecompress(bits);
}

static std::string buildCompound(Strings const &parts) // DecodedText: the first two parts, empty ones dropped, joined by '/'
{
    Strings s; for (size_t i = 0; i < 2 && i < parts.size(); i++) if (!parts[i].empty()) s.push_back(parts[i]);
    return join(s, "/");
}

static std::string latin1ToUtf8(std::string const &s) // the JSC words are Latin-1
{
    std::string o;
    for (unsigned char ch : s) { if (ch < 0x80) o += (char)ch; else { o += (char)(0xC0 | (ch >> 6)); o += (char)(0x80 | (ch & 0x3F)); } }
    return o;
}

// ---- DecodedText ----
Unpacked unpack(std::string const &frame, int bits)
{
    Unpacked u; u.message = frame;                   // (message_ starts as the frame)
    std::string m = trimmed(frame);
    if (m.size() < 12 || m.find(' ') != std::string::npos) return u;
    bool data = (bits & JS8CallData) == JS8CallData;

    if (data) {                                      // tryUnpackFastData
        std::string d = unpackFastDataMessage(m);
        if (!d.empty()) { u.message = latin1ToUtf8(d); u.frameType = FrameData; return u; }
    } else {                                         // tryUnpackData
        std::string d = unpackDataMessage(m);
        if (!d.empty()) { u.message = latin1ToUtf8(d); u.frameType = FrameData; return u; }
    }
    if (!data) {                                     // tryUnpackHeartbeat
        bool isAlt = false; std::uint8_t type = FrameUnknown, bits3 = 0;
        Strings parts = unpackHeartbeatMessage(m, &type, &isAlt, &bits3);
        if (parts.size() >= 2) {
            u.frameType = type; u.isHeartbeat = true; u.isAlt = isAlt;
            std::string extra = parts.size() > 2 ? parts[2] : std::string();
            std::string compound = buildCompound(parts);
            u.from = compound;
            u.message = compound + ": ";
            if (isAlt) u.message += std::string("@ALLCALL ") + (bits3 < 8 ? cqs[bits3] : "");
            else u.message += "@HB HEARTBEAT";      // (every hbString is "HB", shown as HEARTBEAT)
            u.message += " " + extra + " ";
            return u;
        }
    }
    {                                                // tryUnpackCompound
        std::uint8_t type = FrameUnknown, bits3 = 0;
        Strings parts = unpackCompoundMessage(m, &type, &bits3);
        if (parts.size() >= 2 && !data) {
            u.frameType = type;
            std::string extra = join(parts, " ", 2);
            std::string compound = buildCompound(parts);
            u.from = compound;
            if (type == FrameCompound) u.message = compound + ": ";
            else if (type == FrameCompoundDirected) u.message = compound + extra + " ";
            return u;
        }
    }
    if (!data) {                                     // tryUnpackDirected
        std::uint8_t type = FrameUnknown;
        Strings parts = unpackDirectedMessage(m, &type);
        if (!parts.empty()) {
            if (parts.size() == 3 || parts.size() == 4) u.message = parts[0] + ": " + parts[1] + join(parts, " ", 2) + " ";
            else u.message = join(parts, "");
            u.frameType = type; u.from = parts[0]; u.to = parts[1];
            return u;
        }
    }
    return u;                                        // could not unpack: the raw frame
}
}

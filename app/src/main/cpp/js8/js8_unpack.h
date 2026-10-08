// JS8 message unpacking: turns a decoded 12-character JS8 frame and its 3 frame-type bits into the text JS8Call shows
// (heartbeats, CQs, directed messages, compound callsigns, free text). A port to plain C++ of the receive half of
// JS8Call's DecodedText.cpp, varicode.cpp and jsc.cpp (Qt-based), with the same results. (HF Digital Modes)
//
// This file is part of JS8Call. (C) 2018 Jordan Sherer <kn4crd@gmail.com> - All Rights Reserved. Distributed under the
// GNU General Public License v3 (LICENSE).
#pragma once
#include <string>

namespace js8unpack
{
    enum TransmissionType { JS8Call = 0, JS8CallFirst = 1, JS8CallLast = 2, JS8CallData = 4 }; // frame-type bits (varicode.h)
    enum FrameType { FrameUnknown = 255, FrameHeartbeat = 0, FrameCompound = 1, FrameCompoundDirected = 2, FrameDirected = 3, FrameData = 4 };

    struct Unpacked {
        std::string message;                         // as JS8Call shows it (UTF-8)
        int frameType = FrameUnknown;                // which kind of frame
        bool isHeartbeat = false;                    // a heartbeat or CQ
        bool isAlt = false;                          // (heartbeat) the CQ form
        std::string from;                            // the sender's call, where the frame carries it
        std::string to;                              // the call or group addressed (directed frames)
    };

    /** DecodedText(frame, bits): tries fast data, data, heartbeat, compound and directed, in JS8Call's order. */
    Unpacked unpack(std::string const &frame, int bits);
}

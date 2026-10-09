// PC test of JS8 transmit (js8_pack.cpp): heartbeat, CQ, directed messages and free text are packed, turned into JS8
// Normal audio, placed 0.5 s into a noisy 15 s slot and decoded + unpacked by the app's receiver.
#include <cstdio>
#include <random>
#include "js8_pack.h"
#include "js8_run.h"

static void run(const char *what, std::vector<Js8TxFrame> const &frames)
{
    std::mt19937 rng(5); std::normal_distribution<double> g(0, 1);
    printf("%s: %zu frame(s)\n", what, frames.size());
    for (auto const &f : frames) {
        auto a = js8_tx_audio(f, 1200, 0.2);
        std::vector<int16_t> slot(180000);
        for (auto &v : slot) v = (int16_t)(g(rng) * 1500);
        for (size_t i = 0; i < a.size() && i + 6000 < slot.size(); i++) slot[i + 6000] += a[i];
        auto l = js8_decode_slot(slot.data(), (int)slot.size(), 1200);
        printf("   [%s] bits %d -> %s\n", f.frame.c_str(), f.bits, l.empty() ? "NOT DECODED" : (l[0].message + "  (bits " + std::to_string(l[0].bits) + ")").c_str());
    }
}

int main()
{
    run("Heartbeat", js8_heartbeat("M7JVY", "IO91", -1));
    run("CQ", js8_heartbeat("M7JVY", "IO91", 0));
    run("SNR? to G4ABC", js8_directed("M7JVY", "G4ABC", 0, "", ""));
    run("SNR -12 to G4ABC", js8_directed("M7JVY", "G4ABC", 25, "-12", ""));
    run("Text to G4ABC", js8_directed("M7JVY", "G4ABC", 31, "", "HELLO FROM THE PHONE APP"));
    run("Free text", js8_text("M7JVY", "TESTING 123"));
    return 0;
}

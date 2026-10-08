// FT8 / FT4 slot decoder on ft8_lib (Kārlis Goba, MIT), without JNI (ft8_jni.c calls it; a PC test can too): decodes one FT8 (15 s) or FT4 (7.5 s) slot of 12 kHz audio, the way
// ft8_lib's demo/decode_ft8.c does (monitor -> candidates -> LDPC decode -> unpack, duplicates dropped), plus a signal
// to noise estimate in WSJT-X's terms (dB in 2500 Hz) from the waterfall at the decoded message's own tones. Callsign
// hashes (for <...> calls) are remembered between slots, as in the demo.
#include "ft8_slot.h"                                // this file's function
#include <math.h>                                    // log10f
#include <pthread.h>                                 // the lock
#include <stdio.h>                                   // snprintf
#include <stdlib.h>                                  // malloc
#include <string.h>                                  // memcmp, strcpy
#include "ft8/decode.h"                              // candidates, decode
#include "ft8/encode.h"                              // tones of a decoded message (for its SNR)
#include "ft8/message.h"                             // unpacking
#include "common/monitor.h"                          // audio -> waterfall

#ifndef MAX_CANDIDATES
#define MAX_CANDIDATES 140                           // candidates tried a slot (as the demo)
#endif
#ifndef MIN_SCORE
#define MIN_SCORE 10                                 // sync score threshold (as the demo)
#endif
#ifndef LDPC_ITERATIONS
#define LDPC_ITERATIONS 25                           // as the demo
#endif
#define MAX_DECODED 60                               // messages a slot
#define HASH_SIZE 256                                // remembered callsigns

static pthread_mutex_t g_mx = PTHREAD_MUTEX_INITIALIZER; // one decode at a time

// ---- callsign hash table (ft8_lib demo/decode_ft8.c, unchanged in substance) ----
static struct { char callsign[12]; uint32_t hash; } g_calls[HASH_SIZE]; // callsign, age (top 8 bits) + hash (22 bits)
static int g_calls_n;                                // entries in use

static void hash_cleanup(uint8_t max_age)            // forget calls not heard for max_age slots; age the rest
{
    for (int i = 0; i < HASH_SIZE; ++i) {
        if (g_calls[i].callsign[0] == '\0') continue; // empty
        uint8_t age = (uint8_t)(g_calls[i].hash >> 24); // its age
        if (age > max_age) { g_calls[i].callsign[0] = '\0'; g_calls[i].hash = 0; g_calls_n--; } // too old: free it
        else g_calls[i].hash = (((uint32_t)age + 1u) << 24) | (g_calls[i].hash & 0x3FFFFFu); // one slot older
    }
}

static void hash_add(const char *callsign, uint32_t hash) // remember a callsign's hash
{
    uint16_t h10 = (hash >> 12) & 0x3FFu; int i = (h10 * 23) % HASH_SIZE; // starting slot
    while (g_calls[i].callsign[0] != '\0') {
        if (((g_calls[i].hash & 0x3FFFFFu) == hash) && strcmp(g_calls[i].callsign, callsign) == 0) { g_calls[i].hash &= 0x3FFFFFu; return; } // known: age 0 again
        i = (i + 1) % HASH_SIZE;                     // clash: next slot
    }
    if (g_calls_n >= HASH_SIZE - 1) return;          // full (cannot happen with the cleanup, but never loop forever)
    g_calls_n++; strncpy(g_calls[i].callsign, callsign, 11); g_calls[i].callsign[11] = '\0'; g_calls[i].hash = hash; // new
}

static bool hash_lookup(ftx_callsign_hash_type_t type, uint32_t hash, char *callsign) // a callsign from its hash
{
    uint8_t shift = (type == FTX_CALLSIGN_HASH_10_BITS) ? 12 : (type == FTX_CALLSIGN_HASH_12_BITS ? 10 : 0);
    uint16_t h10 = (hash >> (12 - shift)) & 0x3FFu; int i = (h10 * 23) % HASH_SIZE;
    for (int n = 0; n < HASH_SIZE && g_calls[i].callsign[0] != '\0'; n++) {
        if (((g_calls[i].hash & 0x3FFFFFu) >> shift) == hash) { strcpy(callsign, g_calls[i].callsign); return true; } // found
        i = (i + 1) % HASH_SIZE;
    }
    callsign[0] = '\0'; return false;                // unknown
}

static ftx_callsign_hash_interface_t g_hash_if = { .lookup_hash = hash_lookup, .save_hash = hash_add };

// ---- SNR ----
// Noise: the median waterfall level in the band around the signal (about 60 Hz either side of its 8 tones, the
// signal's own bins left out, over the whole slot), turned into a mean power (an exponential distribution's median is
// ln 2 of its mean). Taking it beside the signal, not over the whole band, follows the radio's passband and the band's
// noise, as WSJT-X's baseline does. Signal: the mean power at the message's own tone in each of its symbols, less the
// noise. Reported in 2500 Hz as WSJT-X does: SNR_OFFSET_DB converts from one bin's noise bandwidth (3.125 Hz bins of a
// Hann window), checked against WSJT-X's reports on ft8_lib's test recordings (tools/test).
#define SNR_OFFSET_DB 29.5f                          // 10 log10(2500 Hz / bin noise bandwidth) = 27.3, + 2.2 found against WSJT-X
static float noise_near(const ftx_waterfall_t *wf, const ftx_candidate_t *c)
{
    int hist[256] = {0}; long total = 0;             // histogram of the 0.5 dB steps
    int plane = (c->time_sub * wf->freq_osr + c->freq_sub) * wf->num_bins; // the candidate's sub-block / sub-bin plane
    for (int b = 0; b < wf->num_blocks; b++) {
        const WF_ELEM_T *row = wf->mag + (long)b * wf->block_stride + plane; // this block's bins
        for (int k = c->freq_offset - 20; k < c->freq_offset + 28; k++) {
            if (k < 0 || k >= wf->num_bins || (k >= c->freq_offset - 1 && k <= c->freq_offset + 8)) continue; // outside, or the signal
            hist[row[k]]++; total++;
        }
    }
    if (total == 0) return 0;                        // nothing beside it
    long run = 0; int med = 0;
    for (med = 0; med < 256; med++) { run += hist[med]; if (run >= total / 2) break; } // the median step
    float db = med * 0.5f - 120.0f;                  // in dB
    return powf(10.0f, db / 10.0f) / 0.693f;         // mean power
}

static float snr_db(const ftx_waterfall_t *wf, const ftx_candidate_t *c, const ftx_message_t *msg)
{
    float noise = noise_near(wf, c);                 // the noise beside it
    uint8_t tones[FT4_NN > FT8_NN ? FT4_NN : FT8_NN]; int nn; // the message's tones
    if (wf->protocol == FTX_PROTOCOL_FT4) { ft4_encode(msg->payload, tones); nn = FT4_NN; } else { ft8_encode(msg->payload, tones); nn = FT8_NN; }
    int off = ((c->time_offset * wf->time_osr + c->time_sub) * wf->freq_osr + c->freq_sub) * wf->num_bins + c->freq_offset; // the candidate's first symbol (decode.c get_cand_mag)
    double sum = 0; int n = 0;
    for (int i = 0; i < nn; i++) {
        if (wf->protocol == FTX_PROTOCOL_FT4 && (i == 0 || i == nn - 1)) continue; // FT4's ramp symbols
        int block = c->time_offset + i; if (block < 0 || block >= wf->num_blocks) continue; // outside the slot
        int bin = c->freq_offset + tones[i]; if (bin >= wf->num_bins) continue; // outside the band
        float db = wf->mag[off + i * wf->block_stride + tones[i]] * 0.5f - 120.0f; // the tone's level
        sum += powf(10.0f, db / 10.0f); n++;
    }
    if (n == 0 || noise <= 0) return -30.0f;         // nothing to go on
    double s = sum / n - noise; if (s < noise * 0.001) s = noise * 0.001; // signal alone
    float snr = 10.0f * log10f((float)(s / noise)) - SNR_OFFSET_DB; // in 2500 Hz
    return snr < -30 ? -30 : (snr > 40 ? 40 : snr); // WSJT-X's range
}


// ---- the slot ----
int ft8_decode_slot(const int16_t *samples, int n, bool ft4, char lines[][FT8_LINE], int max_lines)
{
    pthread_mutex_lock(&g_mx);
    monitor_config_t cfg = { .f_min = 200, .f_max = 3000, .sample_rate = 12000, .time_osr = 2, .freq_osr = 2,
                             .protocol = ft4 ? FTX_PROTOCOL_FT4 : FTX_PROTOCOL_FT8 }; // as the demo
    monitor_t mon; monitor_init(&mon, &cfg);         // the waterfall for the slot
    float *frame = (float *)malloc(sizeof(float) * mon.block_size); // one block as floats
    for (int pos = 0; pos + mon.block_size <= n; pos += mon.block_size) { // block by block
        for (int i = 0; i < mon.block_size; i++) frame[i] = samples[pos + i] / 32768.0f;
        monitor_process(&mon, frame);
    }
    free(frame);                                     // done with the audio

    const ftx_waterfall_t *wf = &mon.wf;
    ftx_candidate_t cands[MAX_CANDIDATES];           // the likely signals
    int ncand = ftx_find_candidates(wf, MAX_CANDIDATES, cands, MIN_SCORE);
    static ftx_message_t decoded[MAX_DECODED]; ftx_message_t *table[MAX_DECODED] = {0}; // duplicates check (as the demo)
    int nlines = 0;                                  // results
    for (int k = 0; k < ncand && nlines < max_lines && nlines < MAX_DECODED; k++) {
        const ftx_candidate_t *c = &cands[k];
        ftx_message_t msg; ftx_decode_status_t st;
        if (!ftx_decode_candidate(wf, c, LDPC_ITERATIONS, &msg, &st)) continue; // not a message
        int h = msg.hash % MAX_DECODED; bool dup = false; // seen this slot already?
        while (table[h] != NULL) { if (table[h]->hash == msg.hash && memcmp(table[h]->payload, msg.payload, sizeof(msg.payload)) == 0) { dup = true; break; } h = (h + 1) % MAX_DECODED; }
        if (dup) continue;
        decoded[h] = msg; table[h] = &decoded[h];    // remember it
        char text[FTX_MAX_MESSAGE_LENGTH]; ftx_message_offsets_t offs;
        if (ftx_message_decode(&msg, &g_hash_if, text, &offs) != FTX_MESSAGE_RC_OK) continue; // could not unpack
        float freq = (mon.min_bin + c->freq_offset + (float)c->freq_sub / wf->freq_osr) / mon.symbol_period; // audio Hz
        float t = (c->time_offset + (float)c->time_sub / wf->time_osr) * mon.symbol_period; // seconds from the slot start
        float dt = t - 0.5f - mon.symbol_period;     // WSJT-X's DT: transmissions start 0.5 s into the slot; ft8_lib's times run one symbol late (0.16 s FT8: found against WSJT-X and with generated FT8 / FT4 signals)
        snprintf(lines[nlines++], FT8_LINE, "%.0f\t%.1f\t%.0f\t%s", snr_db(wf, c, &msg), dt, freq, text);
    }
    hash_cleanup(10);                                // age the remembered calls
    monitor_free(&mon);
    pthread_mutex_unlock(&g_mx);
    return nlines;
}

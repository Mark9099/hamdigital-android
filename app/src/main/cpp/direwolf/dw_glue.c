// dw_glue.c  --  Dire Wolf (John Langner, WB2OSZ; GPL v2 or later) as a receiver and transmitter for HF Digital Modes,
// in the way Dire Wolf's own test tools use it: like atest.c, audio samples go straight to multi_modem_process_sample
// and each good frame arrives in dlq_rec_frame (here: formatted, APRS-decoded and queued for the app); like
// gen_packets.c, a frame in TNC2 monitor format ("SRC>DEST,PATH:info") is sent with layer2_preamble_postamble /
// layer2_send_frame and the audio collected from audio_put. The rest of Dire Wolf (its sound cards, PTT, KISS / AGW
// servers, digipeater, iGate, beacons, configuration file) is not used; the few functions the used files call are stood
// in for here. (HF Digital Modes, 2026; GPL as Dire Wolf.)
#include "direwolf.h"                                // Dire Wolf's common definitions
#include <stdio.h>                                   // snprintf
#include <stdlib.h>                                  // malloc
#include <string.h>                                  // strlen
#include "audio.h"                                   // struct audio_s
#include "multi_modem.h"                             // multi_modem_init, multi_modem_process_sample
#include "ax25_pad.h"                                // packets
#include "hdlc_rec.h"                                // hdlc_rec_data_detect_any
#include "hdlc_send.h"                               // layer2_send_frame, layer2_preamble_postamble
#include "gen_tone.h"                                // gen_tone_init
#include "decode_aprs.h"                             // decode_aprs
#include "dlq.h"                                     // dlq_rec_frame's prototype
#include "fx25.h"                                    // fx25_init
#include "il2p.h"                                    // il2p_init
#include "textcolor.h"                               // text_color_init
#include "dw_glue.h"                                 // this file's API

static struct audio_s rx_cfg;                        // the receiver's configuration (atest's my_audio_config)
static char *queue; static size_t qlen, qcap;        // decoded frames, one a line, not yet taken

static void configure(struct audio_s *a, int baud, int rate)   // atest's defaults and its -B logic
{
    memset(a, 0, sizeof(*a));
    a->adev[0].num_channels = 1; a->adev[0].samples_per_sec = rate; a->adev[0].bits_per_sample = 16;
    a->chan_medium[0] = MEDIUM_RADIO;
    a->achan[0].modem_type = MODEM_AFSK;
    a->achan[0].baud = baud;
    if (baud < 600) { a->achan[0].mark_freq = 1600; a->achan[0].space_freq = 1800;  // HF SSB packet
                      a->achan[0].num_freq = 7; a->achan[0].offset = 30; }          // (7 demodulators 30 Hz apart: HF tuning, as Dire Wolf's guide suggests)
    else { a->achan[0].mark_freq = DEFAULT_MARK_FREQ; a->achan[0].space_freq = DEFAULT_SPACE_FREQ; a->achan[0].num_freq = 1; } // 1200 / 2200 Hz (VHF / UHF FM)
    strlcpy(a->achan[0].profiles, "A", sizeof(a->achan[0].profiles));
    a->achan[0].fix_bits = RETRY_NONE; a->achan[0].sanity_test = SANITY_APRS; a->achan[0].passall = 0;
    a->achan[0].layer2_xmit = LAYER2_AX25;
}

void dwg_init(int baud, int rate)
{
    static int once = 0;
    if (!once) { text_color_init(0); fx25_init(0); il2p_init(0); once = 1; }
    configure(&rx_cfg, baud, rate);
    multi_modem_init(&rx_cfg);
    qlen = 0;
}

void dwg_process(const short *s, int n) { for (int i = 0; i < n; i++) multi_modem_process_sample(0, s[i]); }

static void put(const char *t)                       // queue a line
{
    size_t n = strlen(t);
    if (qlen + n + 2 > qcap) { qcap = (qlen + n + 2) * 2; queue = realloc(queue, qcap); }
    memcpy(queue + qlen, t, n); qlen += n; queue[qlen++] = '\n'; queue[qlen] = 0;
}

int dwg_take(char *out, int max)                     // the lines queued (each: see dw_glue.h), then forget them
{
    if (qlen == 0) { if (max > 0) out[0] = 0; return 0; }
    int n = (int)(qlen < (size_t)max - 1 ? qlen : (size_t)max - 1);
    memcpy(out, queue, n); out[n] = 0;
    memmove(queue, queue + n, qlen - n); qlen -= n;
    return n;
}

static void clean(char *s) { for (; *s; s++) if (*s == '\t' || *s == '\n' || *s == '\r' || (unsigned char)*s < 32) *s = ' '; } // (one line, tab-separated)

// A good frame (Dire Wolf calls this; atest.c's version prints it): monitor text, who it was heard from, APRS decode.
void dlq_rec_frame(int chan, int subchan, int slice, packet_t pp, alevel_t alevel, fec_type_t fec_type, retry_t retries, char *spectrum)
{
    char addrs[500], heard[AX25_MAX_ADDR_LEN + 4] = "", info[600], lvl[AX25_ALEVEL_TO_TEXT_SIZE], line[2600];
    unsigned char *pinfo; int ilen;
    ax25_format_addrs(pp, addrs);                    // "SRC>DEST,PATH:"
    ilen = ax25_get_info(pp, &pinfo);
    int k = 0; for (int i = 0; i < ilen && k < (int)sizeof(info) - 1; i++) { unsigned char c = pinfo[i]; info[k++] = (c >= 32 && c < 127) ? (char)c : '.'; } info[k] = 0; // printable
    if (ax25_get_num_addr(pp) > 0) ax25_get_addr_with_ssid(pp, ax25_get_heard(pp), heard); // the station heard (source or digipeater)
    ax25_alevel_to_text(alevel, lvl);
    decode_aprs_t A; memset(&A, 0, sizeof(A));
    int aprs = ax25_is_aprs(pp);
    if (aprs) decode_aprs(&A, pp, 1, NULL);           // (quiet)
    clean(addrs); clean(info); clean(A.g_data_type_desc); clean(A.g_comment); clean(A.g_name); clean(A.g_mfr); clean(A.g_weather);
    // monitor text \t heard from \t level \t APRS? \t lat \t lon \t symbol table+code \t type \t name/object \t comment \t speed mph \t course \t altitude ft \t weather \t device
    snprintf(line, sizeof line, "%s%s\t%s\t%s\t%d\t%.6f\t%.6f\t%c%c\t%s\t%s\t%s\t%.0f\t%.0f\t%.0f\t%s\t%s",
             addrs, info, heard, lvl, aprs, aprs ? A.g_lat : G_UNKNOWN, aprs ? A.g_lon : G_UNKNOWN,
             aprs && A.g_symbol_table ? A.g_symbol_table : ' ', aprs && A.g_symbol_code ? A.g_symbol_code : ' ',
             A.g_data_type_desc, A.g_name, A.g_comment, aprs ? A.g_speed_mph : G_UNKNOWN, aprs ? A.g_course : G_UNKNOWN,
             aprs ? A.g_altitude_ft : G_UNKNOWN, A.g_weather, A.g_mfr);
    put(line);
    ax25_delete(pp);
    (void)chan; (void)subchan; (void)slice; (void)fec_type; (void)retries; (void)spectrum;
}

// ---- transmit (gen_packets.c's way) ----
static short *tx; static int txn, txcap;              // the audio being made
static int tx_lo = -1;                               // (audio_put gets bytes: the low one first)

int audio_put(int a, int c)                          // Dire Wolf's sound output: 16-bit little-endian, a byte at a time
{
    (void)a;
    if (tx_lo < 0) { tx_lo = c & 0xff; return c; }
    if (txn >= txcap) { txcap = txcap ? txcap * 2 : 65536; tx = realloc(tx, sizeof(short) * txcap); }
    tx[txn++] = (short)((tx_lo & 0xff) | ((c & 0xff) << 8)); tx_lo = -1;
    return c;
}
int audio_flush(int a) { (void)a; return 0; }

int dwg_encode(const char *tnc2, int baud, int rate, double amplitude, short **out)
{
    static struct audio_s txc;
    configure(&txc, baud, rate);
    packet_t pp = ax25_from_text((char *)tnc2, 1);   // (strict)
    if (pp == NULL) { *out = NULL; return -1; }       // not valid TNC2 monitor format
    txn = 0; tx_lo = -1;
    gen_tone_init(&txc, (int)(amplitude * 100) / 2, 1); // (gen_packets: amplitude percent / 2)
    int spb = rate / baud; for (int j = 0; j < spb * 8; j++) gen_tone_put_sample(0, 0, 0); // a little silence first
    layer2_preamble_postamble(0, baud < 600 ? 16 : 32, 0, &txc); // flags: let the receivers lock on (fewer at 300 baud: each is slow)
    layer2_send_frame(0, pp, 0, &txc);
    layer2_preamble_postamble(0, 2, 1, &txc);
    for (int j = 0; j < spb * 4; j++) gen_tone_put_sample(0, 0, 0);
    ax25_delete(pp);
    *out = malloc(sizeof(short) * (txn > 0 ? txn : 1)); memcpy(*out, tx, sizeof(short) * txn);
    return txn;
}

// ---- stand-ins for the parts of Dire Wolf not used here ----
void ptt_set(int ot, int chan, int ptt_signal) { (void)ot; (void)chan; (void)ptt_signal; } // (DCD output: none)
int get_input(int it, int chan) { (void)it; (void)chan; return -1; }
int audio_get(int a) { (void)a; return -1; }          // (the audio comes in through dwg_process)

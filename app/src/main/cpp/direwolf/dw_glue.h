// dw_glue.h  --  Dire Wolf as a receiver and transmitter for HF Digital Modes (dw_glue.c). One receiver; not thread-safe
// (the JNI bridge locks round it).
#pragma once

/** Set the receiver up for [baud] (300: HF, 1600/1800 Hz, seven demodulators 30 Hz apart; 1200: VHF/UHF FM,
 *  1200/2200 Hz) at [rate] samples a second. */
void dwg_init(int baud, int rate);
/** Audio in: 16-bit samples at the rate set. */
void dwg_process(const short *s, int n);
/** The frames decoded since the last call, one a line, tab-separated: monitor text (SRC>DEST,PATH:info), heard from,
 *  audio level, APRS 0/1, latitude, longitude (unknown: -999999), symbol (table + code), APRS type, object name,
 *  comment, speed mph, course, altitude ft, weather, device. Returns the bytes copied. */
int dwg_take(char *out, int max);
/** The audio for one frame in TNC2 monitor format, at [baud] / [rate]; peak [amplitude] 0..1. Returns the samples
 *  (and *out, to free), or -1 if the text is not a valid frame. */
int dwg_encode(const char *tnc2, int baud, int rate, double amplitude, short **out);

// FT8 / FT4 slot decoder (ft8_slot.c, on ft8_lib): used by the app through ft8_jni.c and by the PC test (tools/test).
#pragma once
#include <stdbool.h>                                 // bool
#include <stdint.h>                                  // int16_t

#define FT8_LINE 128                                 // one result line's room

#ifdef __cplusplus
extern "C" {
#endif

/** Decode one slot: n samples of 12 kHz mono audio from the slot's start. Fills lines with "snr\tdt\tfreq\ttext"
 *  (at most max_lines) and returns how many. */
int ft8_decode_slot(const int16_t *samples, int n, bool ft4, char lines[][FT8_LINE], int max_lines);

/** Transmit audio for a message: 12 kHz, the signal only (79 FT8 / 105 FT4 symbols), base tone f0 Hz, peak
 *  amplitude 0..1. Returns the samples written, or -1 if the text is not a message FT8 / FT4 can send. */
int ft8_encode_audio(const char *text, bool ft4, float f0, int16_t *out, int max_samples, float amplitude);

#ifdef __cplusplus
}
#endif

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

#ifdef __cplusplus
}
#endif

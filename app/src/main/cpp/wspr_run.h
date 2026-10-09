// WSPR slot decoder (wspr_run.c, on WSJT-X's wsprd): used by the app through wspr_jni.c and by the PC test.
#pragma once
#include <stdint.h>                                  // int16_t

#ifdef __cplusplus
extern "C" {
#endif

/** Decode a WSPR slot saved as wav (12 kHz 16-bit mono, 44-byte header, named yymmdd_hhmm.wav, from the even minute)
 *  with the radio's dial at dial_mhz; wsprd keeps its files (hash table, ALL_WSPR.TXT) in data_dir. Copies wsprd's
 *  spots file into out ("date time sync snr dt freq message drift cycles jitter" a line) and returns its length, or
 *  -1 if wsprd failed. */
int wspr_decode_file(const char *wav, const char *data_dir, double dial_mhz, char *out, int out_size);

/** WSPR transmit audio for message ("CALL GRID DBM"): 162 symbols of 4-FSK centred on f0 Hz, 12 kHz, 110.6 s, peak
 *  amplitude 0..1. Returns the samples written, or -1 if it cannot be encoded. */
int wspr_encode_audio(const char *message, float f0, int16_t *out, int max_samples, float amplitude);

#ifdef __cplusplus
}
#endif

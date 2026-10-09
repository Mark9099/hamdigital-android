/* Opus build settings for the parts RADE uses (LPCNet feature extraction, the pitch DNN, FARGAN and the nnet core):
   what Opus's configure would write, for a float build without run-time CPU detection. The vector code is chosen at
   compile time by dnn/vec.h (NEON on ARM, SSE/AVX on x86). */
#ifndef RADE_OPUS_CONFIG_H
#define RADE_OPUS_CONFIG_H
#define OPUS_BUILD 1                                  /* building Opus itself (its internal headers) */
#define VAR_ARRAYS 1                                  /* C99 variable-length arrays for scratch space */
#define HAVE_LRINTF 1                                 /* lrintf() is there (float to int rounding) */
#define HAVE_LRINT 1                                  /* lrint() too */
#endif

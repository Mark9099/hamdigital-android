// FFTW stand-in for the decoders that call FFTW's single-precision API (wsprd from WSJT-X, the JS8Call decoder): the
// handful of calls they use, done with KISS FFT (in ft8_lib/fft, BSD licence) so FFTW need not be built for Android.
// KISS FFT handles any size whose factors are 2, 3, 4 and 5 - every size these decoders use (e.g. wsprd's 1474560 and
// 46080, JS8's 180000). Like FFTW, transforms are unnormalised and the forward sign is exp(-i...); in-place works.
#pragma once
#include <stddef.h>                                  // size_t

#ifdef __cplusplus
extern "C" {
#endif

typedef float fftwf_complex[2];                      // re, im (the same layout as kiss_fft_cpx)
typedef struct fftwf_plan_s *fftwf_plan;             // a prepared transform

#define FFTW_FORWARD (-1)                            // exp(-i ...)
#define FFTW_BACKWARD (+1)                           // exp(+i ...)
#define FFTW_MEASURE (0U)                            // planning flags: all the same here
#define FFTW_ESTIMATE (1U << 6)
#define FFTW_PATIENT (1U << 5)
#define FFTW_EXHAUSTIVE (1U << 3)
#define FFTW_ESTIMATE_PATIENT (1U << 7)              // (FFTW 3.3; JS8Call uses it)

void *fftwf_malloc(size_t n);                        // memory for transforms
void fftwf_free(void *p);
fftwf_plan fftwf_plan_dft_1d(int n, fftwf_complex *in, fftwf_complex *out, int sign, unsigned flags); // complex -> complex
fftwf_plan fftwf_plan_dft_r2c_1d(int n, float *in, fftwf_complex *out, unsigned flags); // real -> n/2+1 complex
void fftwf_execute(const fftwf_plan p);              // run it on the arrays it was made with
void fftwf_destroy_plan(fftwf_plan p);               // free it
void fftwf_cleanup(void);                            // (nothing to do)
int fftwf_import_wisdom_from_file(void *f);          // wisdom: not used (returns 0, "none read")
void fftwf_export_wisdom_to_file(void *f);           // (nothing written)
int fftwf_import_wisdom_from_filename(const char *f);
int fftwf_export_wisdom_to_filename(const char *f);
void fftwf_forget_wisdom(void);

#ifdef __cplusplus
}
#endif

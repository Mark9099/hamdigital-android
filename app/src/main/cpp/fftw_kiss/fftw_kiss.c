// FFTW stand-in on KISS FFT (see fftw3.h): plans remember the arrays and a KISS configuration; execute runs it.
#include "fftw3.h"                                   // the API
#include <stdlib.h>                                  // malloc
#include "fft/kiss_fft.h"                            // complex transforms
#include "fft/kiss_fftr.h"                           // real -> complex

struct fftwf_plan_s {
    int real;                                        // 1: real -> complex
    void *in, *out;                                  // the arrays
    kiss_fft_cfg c;                                  // complex configuration
    kiss_fftr_cfg r;                                 // real configuration
};

void *fftwf_malloc(size_t n) { return malloc(n); }   // (KISS needs no special alignment)
void fftwf_free(void *p) { free(p); }

fftwf_plan fftwf_plan_dft_1d(int n, fftwf_complex *in, fftwf_complex *out, int sign, unsigned flags)
{
    (void)flags;                                     // (no planning modes)
    fftwf_plan p = (fftwf_plan)calloc(1, sizeof(*p)); if (!p) return NULL;
    p->in = in; p->out = out;
    p->c = kiss_fft_alloc(n, sign == FFTW_BACKWARD, NULL, NULL); // inverse for FFTW_BACKWARD (unnormalised in both)
    if (!p->c) { free(p); return NULL; }
    return p;
}

fftwf_plan fftwf_plan_dft_r2c_1d(int n, float *in, fftwf_complex *out, unsigned flags)
{
    (void)flags;
    fftwf_plan p = (fftwf_plan)calloc(1, sizeof(*p)); if (!p) return NULL;
    p->real = 1; p->in = in; p->out = out;
    p->r = kiss_fftr_alloc(n, 0, NULL, NULL);        // forward real transform (n even)
    if (!p->r) { free(p); return NULL; }
    return p;
}

void fftwf_execute(const fftwf_plan p)
{
    if (!p) return;
    if (p->real) kiss_fftr(p->r, (const kiss_fft_scalar *)p->in, (kiss_fft_cpx *)p->out); // (reads all input before writing: in place is safe)
    else kiss_fft(p->c, (const kiss_fft_cpx *)p->in, (kiss_fft_cpx *)p->out);            // (in place handled by KISS)
}

void fftwf_destroy_plan(fftwf_plan p)
{
    if (!p) return;
    if (p->real) kiss_fftr_free(p->r); else kiss_fft_free(p->c); // its configuration
    free(p);
}

void fftwf_cleanup(void) {}                          // nothing kept
int fftwf_import_wisdom_from_file(void *f) { (void)f; return 0; }
void fftwf_export_wisdom_to_file(void *f) { (void)f; }
int fftwf_import_wisdom_from_filename(const char *f) { (void)f; return 0; }
int fftwf_export_wisdom_to_filename(const char *f) { (void)f; return 0; }
void fftwf_forget_wisdom(void) {}

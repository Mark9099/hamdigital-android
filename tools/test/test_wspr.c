// PC test of the app's WSPR decoder (app/src/main/cpp/wspr_run.c + wsprd + the FFTW stand-in on KISS FFT, the same
// files the app compiles): decodes WSPR WAV files and prints wsprd's spots. Build and run: tools/test/run_wspr.sh.
#include <stdio.h>                                   // printf
#include "wspr_run.h"                                // the decoder under test

int main(int argc, char **argv)
{
    static char out[65536];                          // the spots
    const char *dir = argc > 1 ? argv[1] : ".";      // wsprd's data folder
    for (int a = 2; a < argc; a++) {
        int n = wspr_decode_file(argv[a], dir, 14.0956, out, sizeof out); // (dial 14.0956 MHz: WSJT-X's sample is 20 m)
        printf("== %s: %s\n%s", argv[a], n < 0 ? "wsprd failed" : "", n > 0 ? out : "");
    }
    return 0;
}

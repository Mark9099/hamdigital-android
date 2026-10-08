// WSPR: runs WSJT-X's wsprd (its main() compiled as wsprd_main, see CMakeLists.txt) on one 2-minute slot saved as a
// WAV file, the way WSJT-X runs the wsprd program, and returns what it wrote to wspr_spots.txt. No JNI here, so the PC
// test can call it too (wspr_jni.c is the app's wrapper).
#include "wspr_run.h"                                // this file's function
#include <stdio.h>                                   // files
#include <string.h>                                  // strlen
#include <unistd.h>                                  // optind (wsprd reads its options with getopt)
#include <pthread.h>                                 // one run at a time (wsprd uses globals)
#if defined(__BIONIC__)
#include <getopt.h>                                  // optreset (Android declares it here)
#endif

int wsprd_main(int argc, char *argv[]);              // wsprd.c's main()

/* The OSD decoder (osdwspr.f90) is Fortran and only used with wsprd's -o option, which is never given here; this
   stands in for it so wsprd links. */
void osdwspr_(float s[], unsigned char apmask[], int *ndeep, unsigned char cw[], int *nhardmin, float *dmin)
{
    (void)s; (void)apmask; (void)ndeep; (void)cw; *nhardmin = -1; *dmin = 0; // (not reached)
}

static pthread_mutex_t g_mx = PTHREAD_MUTEX_INITIALIZER; // one run at a time

int wspr_decode_file(const char *wav, const char *data_dir, double dial_mhz, char *out, int out_size)
{
    pthread_mutex_lock(&g_mx);
    char freq[32]; snprintf(freq, sizeof freq, "%.6f", dial_mhz); // -f: the dial frequency (for the spots' RF frequencies)
    char *argv[] = { "wsprd", "-a", (char *)data_dir, "-f", freq, (char *)wav, NULL }; // as WSJT-X runs it (no -o: no OSD)
    optind = 1;                                      // getopt from the start again (wsprd_main runs many times in one process)
#if defined(__BIONIC__) || defined(__APPLE__)
    optreset = 1;                                    // (bionic and BSD keep extra getopt state)
#endif
    int rc = wsprd_main(6, argv);                    // decode
    out[0] = '\0'; int n = 0;                        // its spots
    if (rc == 0) {
        char path[512]; snprintf(path, sizeof path, "%s/wspr_spots.txt", data_dir);
        FILE *f = fopen(path, "r");
        if (f) { n = (int)fread(out, 1, (size_t)(out_size - 1), f); out[n] = '\0'; fclose(f); }
    }
    pthread_mutex_unlock(&g_mx);
    return rc == 0 ? n : -1;                         // characters, or -1 if wsprd failed
}

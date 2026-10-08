// PC test of the app's FT8 / FT4 slot decoder (app/src/main/cpp/ft8_slot.c, the same file the app compiles): decodes
// 12 kHz 16-bit mono WAV files and prints the messages as the app would list them, to compare with WSJT-X's decodes
// (ft8_lib's test/wav/*.txt). Build and run: tools/test/run_ft8.sh.
#include <stdio.h>                                   // printf, files
#include <stdlib.h>                                  // malloc
#include <string.h>                                  // memcmp
#include "ft8_slot.h"                                // the decoder under test

/** Read a 16-bit mono WAV's samples; returns how many (0 on failure). */
static int read_wav(const char *path, int16_t **out, int *rate)
{
    FILE *f = fopen(path, "rb"); if (!f) return 0;   // open
    unsigned char h[12]; if (fread(h, 1, 12, f) != 12 || memcmp(h, "RIFF", 4) || memcmp(h + 8, "WAVE", 4)) { fclose(f); return 0; } // RIFF WAVE
    int n = 0; *rate = 0;
    for (;;) {                                       // chunks
        unsigned char c[8]; if (fread(c, 1, 8, f) != 8) break;
        unsigned len = c[4] | c[5] << 8 | c[6] << 16 | (unsigned)c[7] << 24; // chunk length
        if (!memcmp(c, "fmt ", 4)) { unsigned char fm[16]; fread(fm, 1, 16, f); *rate = fm[4] | fm[5] << 8 | fm[6] << 16; fseek(f, len - 16, SEEK_CUR); } // sample rate
        else if (!memcmp(c, "data", 4)) { *out = malloc(len); n = (int)fread(*out, 2, len / 2, f); break; } // the samples
        else fseek(f, len, SEEK_CUR);                // skip
    }
    fclose(f); return n;
}

int main(int argc, char **argv)
{
    int ft4 = 0, total = 0;                          // FT4?, messages over all files
    for (int a = 1; a < argc; a++) {
        if (!strcmp(argv[a], "-ft4")) { ft4 = 1; continue; } // following files are FT4
        int16_t *s = NULL; int rate = 0; int n = read_wav(argv[a], &s, &rate); // the file
        if (n == 0 || rate != 12000) { printf("%s: not a 12 kHz WAV\n", argv[a]); free(s); continue; }
        static char lines[60][FT8_LINE];
        int k = ft8_decode_slot(s, n, ft4, lines, 60); // decode
        printf("== %s: %d\n", argv[a], k); total += k;
        for (int i = 0; i < k; i++) printf("%s\n", lines[i]); // snr dt freq text
        free(s);
    }
    printf("TOTAL %d\n", total);
    return 0;
}

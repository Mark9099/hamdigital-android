// PC test only: functions Android's C library has that Windows' lacks (forced into every file with -include).
#pragma once
#include <string.h>                                  // strlen, memcpy
#ifdef _WIN32
static inline char *stpcpy(char *d, const char *s) { size_t n = strlen(s); memcpy(d, s, n + 1); return d + n; } // copy, return the end
#endif

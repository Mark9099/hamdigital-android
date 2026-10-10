// ----------------------------------------------------------------------------
// wefax_rx.cxx  --  weather fax receiver: fldigi's wefax modem (src/wefax/wefax.cxx, fldigi 4.1.23), receive only.
// Kept from fldigi's fax_implementation: the ACfax input filters (narrow / medium / wide, unchanged), the FM
// demodulator (rx_new_samples: mix to baseband at the carrier, filter, phase difference -> grey 0..255), decode, the APT
// start / stop detection by counting black-white transitions (decode_apt), the phasing-line detection and centring
// (decode_phasing), the pixels (decode_image), the line-to-line correlation that tells a picture from noise
// (correlation_*), skip_apt_rx / skip_phasing_* / end_rx, and save_automatic's rules for which pictures are worth
// keeping. Removed: the picture window (here the pixels are kept in a vector), the waterfall-based power tests in
// fax_signal (only its correlation part is kept) and the AFC (both read fldigi's waterfall), transmit, files, XML-RPC.
// Changed: the sine / cosine lookup tables are a phase accumulator. See ANDROID_CHANGES.txt.
//
// Copyright (C) 2010
//		Remi Chateauneu, F4ECW
// (Receive-only edition 2026, HF Digital Modes)
//
// This is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License
// as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later
// version. It is distributed WITHOUT ANY WARRANTY; see the GNU General Public License (LICENSE).
// ----------------------------------------------------------------------------
#include "wefax_rx.h"                                // this class
#include <cmath>                                     // cos, sin, sqrt
#include <cstring>                                   // strncmp
#include <algorithm>                                 // min, max, fill
#include "complex.h"                                 // cmplx
#include "filters.h"                                 // C_FIR_filter

// ---- fldigi wefax.cxx: the input filters (from ACfax), unchanged ----
#define MAX_FILT_SIZE 256

struct fir_coeffs
{
	const char * _name ;
	int          _size ;
	const double _coefs[MAX_FILT_SIZE];
};

// Narrow, middle and wide fir low pass filter from ACfax
static const fir_coeffs input_filters[] = {
{ "Narrow", 65,
{
  0.000495,  0.000684,  0.000885,  0.00109,   0.00128,  
  0.00141,   0.00142,   0.00124,   0.000793,  2.94e-05,  
 -0.00108,  -0.00251,  -0.0042,   -0.00602,  -0.00778,  
 -0.00925,  -0.0101,   -0.0102,   -0.00909,  -0.00664,  
 -0.00267,   0.00289,   0.01,      0.0185,    0.0281,  
  0.0383,    0.0488,    0.059,     0.0682,    0.0761,  
  0.0821,    0.0858,    0.0871,    0.0858,    0.0821,  
  0.0761,    0.0682,    0.059,     0.0488,    0.0383,  
  0.0281,    0.0185,    0.01,      0.00289,  -0.00267,  
 -0.00664,  -0.00909,  -0.0102,   -0.0101,   -0.00925,  
 -0.00778,  -0.00602,  -0.0042,   -0.00251,  -0.00108,  
  2.94e-05,  0.000793,  0.00124,   0.00142,   0.00141,  
  0.00128,   0.00109,   0.000885,  0.000684,  0.000495
} },
{ "Medium", 65,
{
 -0.000795, -0.000779, -0.000698, -0.000517, -0.000195,  
  0.000303,  0.000982,  0.0018,    0.00267,   0.00343,  
  0.00389,   0.00383,   0.00306,   0.00144,  -0.00102,  
 -0.0042,   -0.0078,   -0.0114,   -0.0143,   -0.0159,  
 -0.0156,   -0.0127,   -0.00695,   0.00188,   0.0136,  
  0.0277,    0.0434,    0.0596,    0.0752,    0.0889,  
  0.0997,    0.106,     0.109,     0.106,     0.0997,  
  0.0889,    0.0752,    0.0596,    0.0434,    0.0277,  
  0.0136,    0.00188,  -0.00695,  -0.0127,   -0.0156,  
 -0.0159,   -0.0143,   -0.0114,   -0.0078,   -0.0042,  
 -0.00102,   0.00144,   0.00306,   0.00383,   0.00389,  
  0.00343,   0.00267,   0.0018,    0.000982,  0.000303,  
 -0.000195, -0.000517, -0.000698, -0.000779, -0.000795
} },
{ "Wide",   65,
{
  0.000716,  0.000844,  0.000845,  0.000668,  0.000259,  
 -0.000402, -0.00126,  -0.00216,  -0.00284,  -0.003,  
 -0.00234,  -0.00073,   0.00175,   0.0047,    0.00747,  
  0.00922,   0.00911,   0.00655,   0.00143,  -0.00575,  
 -0.0138,   -0.0209,   -0.025,    -0.0241,   -0.0167,  
 -0.00203,   0.0193,    0.0457,    0.0743,    0.102,  
  0.125,     0.14,      0.145,     0.14,      0.125,  
  0.102,     0.0743,    0.0457,    0.0193,   -0.00203,  
 -0.0167,   -0.0241,   -0.025,    -0.0209,   -0.0138,  
 -0.00575,   0.00143,   0.00655,   0.00911,   0.00922,  
  0.00747,   0.0047,    0.00175,  -0.00073,  -0.00234,  
 -0.003,    -0.00284,  -0.00216,  -0.00126,  -0.000402,  
  0.000259,  0.000668,  0.000845,  0.000844,  0.000716
} }
};

static const size_t nb_filters = sizeof(input_filters)/sizeof(input_filters[0]); ;

#define GARBAGE_STR "garbage"                        // (fldigi)
static const int MAX_ROWS = 4000;                    // fldigi's WEFAX_MaxRows default
static const double CORRELATION = 0.05;              // fldigi's wefax_correlation default (threshold)
static const int CORR_ROWS = 15;                     // fldigi's wefax_correlation_rows default
#define CLIP 0.001                                   // (fldigi)
static C_FIR_filter *firs(void *p) { return static_cast<C_FIR_filter *>(p); }

WefaxRx::WefaxRx(int ioc)                            // fldigi fax_implementation::fax_implementation + init_rx
{
    if (ioc == 288) { m_apt_start_freq = 675; m_default_lpm = 60; } // IOC 288
    else { ioc = 576; m_apt_start_freq = 300; m_default_lpm = 120; } // IOC 576 (nearly all stations)
    m_img_width = (int)(ioc * M_PI);                 // (fldigi ioc_to_width)
    C_FIR_filter *f = new C_FIR_filter[nb_filters];  // (fldigi fir_filter_pair_set: the same filter for I and Q)
    for (size_t i = 0; i < nb_filters; i++) f[i].init(input_filters[i]._size, 1, const_cast<double *>(input_filters[i]._coefs), const_cast<double *>(input_filters[i]._coefs));
    m_fir = f;
    m_hist.assign(256, 0);
    set_shift(800);                                  // the standard shift
    m_rx_state = RXAPTSTART;                         // (init_rx)
    m_apt_count = m_apt_trans = 0; m_apt_high = false; m_corr_calls_nb = 0;
}

WefaxRx::~WefaxRx() { delete[] firs(m_fir); }

void WefaxRx::set_carrier(double f) { m_carrier = f; }
void WefaxRx::set_shift(int hz) { if (hz < 100 || hz > 1000) hz = 800; deviation_ratio = ((double)SAMPLE_RATE / hz) / (2 * M_PI); } // (fldigi init_rx)

void WefaxRx::reset_phasing_counters()               // fldigi reset_phasing_counters
{
    m_lpm_sum_rx = 0; m_phasing_calls_nb = 0;
    m_phase_high = m_current_value >= 128;
    m_curr_phase_len = m_curr_phase_high = m_curr_phase_low = 0;
    m_phase_lines = m_num_phase_lines = 0;
}

void WefaxRx::rx_process(const double *audio, int n) // fldigi rx_new_samples: FM demodulation to grey levels
{
    std::vector<int> demod; demod.reserve(n);
    C_FIR_filter &filt = firs(m_fir)[m_filter];
    double step = 2 * M_PI * m_carrier / SAMPLE_RATE;
    cmplx prev(prevz.re, prevz.im), currz;
    for (int i = 0; i < n; i++) {
        m_phase += step; if (m_phase > 2 * M_PI) m_phase -= 2 * M_PI;
        if (!filt.run(cmplx(audio[i] * cos(m_phase), audio[i] * sin(m_phase)), currz)) continue;
        int v;
        if (abs(currz) <= CLIP && abs(prev) <= CLIP) v = 255; // white
        else { int ix = (int)round(255 * (0.5 - deviation_ratio * arg(conj(prev) * currz))); v = std::min(std::max(0, ix), 255); }
        prev = currz;
        demod.push_back(v);
    }
    prevz.re = prev.real(); prevz.im = prev.imag();
    decode(demod.data(), (int)demod.size());
}

WefaxRx::State WefaxRx::signal_state()               // fldigi fax_signal::set_state, the correlation part only
{
    const char *stop_code = "";
    State state = IDLE;
    State corr = correlation_state(&stop_code);
    if (corr == RXAPTSTOP) state = RXAPTSTOP;        // ("No significant line-to-line correlation")
    else if (corr == RXIMAGE) state = RXIMAGE;       // ("Significant line-to-line correlation")
    m_sig_stop = stop_code;
    return state;
}

void WefaxRx::decode(const int *buf, int nb)          // fldigi decode (automatic mode)
{
    for (int i = 0; i < nb; i++) {
        int x = buf[i];
        if ((m_sig_cnt++ % 1000) == 0) m_sig_state = signal_state(); // (fldigi fax_signal::refresh)
        m_current_value = x;
        correlation_update(x);
        decode_apt(x, m_sig_state);
        if (m_rx_state == RXPHASING || m_rx_state == RXIMAGE) decode_phasing(x, m_sig_state);
        if (m_rx_state == RXIMAGE && m_lpm_img > 0) decode_image(x);
        m_img_sample++;
    }
}

static bool near_freq(int f1, int f2, int margin) { return abs(f1 - f2) < margin; } // (fldigi is_near_freq)

void WefaxRx::decode_apt(int x, State sig)           // fldigi decode_apt
{
    if (x > 215 && !m_apt_high) { m_apt_high = true; ++m_apt_trans; }
    else if (x < 40 && m_apt_high) m_apt_high = false;
    ++m_apt_count;
    if (m_apt_count < SAMPLE_RATE / 2) return;       // every half second: the transition rate
    m_cr_3 = m_cr_2; m_cr_2 = m_cr_1; m_cr_1 = m_last_apt_freq;
    int curr = m_last_apt_freq = SAMPLE_RATE * m_apt_trans / m_apt_count;
    m_apt_count = m_apt_trans = 0;
    if (m_rx_state == RXAPTSTART) {
        if (near_freq(curr, m_apt_start_freq, 8) && near_freq(m_cr_1, m_apt_start_freq, 8)) { skip_apt_rx(); return; } // APT start
        if (near_freq(curr, m_apt_stop_freq, 2) && near_freq(m_cr_1, m_apt_stop_freq, 2)) return; // (a stray stop while waiting)
    }
    if (m_rx_state == RXIMAGE) {                     // APT start in the middle of a picture: its stop was missed
        const char *msg = nullptr;
        if (near_freq(curr, m_apt_start_freq, 4) && near_freq(m_cr_1, m_apt_start_freq, 4) && near_freq(m_cr_2, m_apt_start_freq, 4)) msg = "apt";
        else if (near_freq(curr, m_apt_start_freq, 5) && near_freq(m_cr_1, m_apt_start_freq, 5) && near_freq(m_cr_2, m_apt_start_freq, 5) && near_freq(m_cr_3, m_apt_start_freq, 5)) msg = "apt2";
        if (msg) { save_automatic(msg); skip_apt_rx(); return; }
    }
    const char *stop = nullptr;                      // APT stop
    if (near_freq(curr, m_apt_stop_freq, 6) && near_freq(m_cr_1, m_apt_stop_freq, 6)) stop = "ok";
    else if (near_freq(curr, m_apt_stop_freq, 7) && near_freq(m_cr_1, m_apt_stop_freq, 7) && near_freq(m_cr_2, m_apt_stop_freq, 7)) stop = "ok2";
    if (stop) { save_automatic(stop); return; }
    switch (sig) {                                   // what the correlation says
    case RXIMAGE: if (m_rx_state == RXAPTSTART) skip_apt_rx(); break; // a picture without its APT start: phase it
    case RXAPTSTOP: if (m_rx_state == RXIMAGE) save_automatic(m_sig_stop); break; // no more picture
    default: break;
    }
}

void WefaxRx::decode_phasing(int x, State sig)       // fldigi decode_phasing
{
    phasing_history[phasing_count % 16] = x;         // a moving average over 16 samples (the black bands are wide)
    ++phasing_count;
    if (phasing_count >= 16) { x = 0; for (int i = 0; i < 16; ++i) x += phasing_history[i]; x /= 16; }
    m_curr_phase_len++;
    ++m_phasing_calls_nb;
    if (x > 188) m_curr_phase_high++;
    else if (x < 68) m_curr_phase_low++;
    if (x > 200 && !m_phase_high) m_phase_high = true;
    else if (x < 25 && m_phase_high) {
        m_phase_high = false;
        // a phasing line: about 5 % white, 95 % black, 60-360 lines a minute
        if (m_curr_phase_high >= 0.04 * m_curr_phase_len && m_curr_phase_low >= 0.94 * m_curr_phase_len && m_curr_phase_len >= 0.4 * SAMPLE_RATE) {
            double tmp_lpm = 60.0 * SAMPLE_RATE / m_curr_phase_len;
            m_lpm_sum_rx += tmp_lpm;
            ++m_phase_lines;
            m_num_phase_lines = 0;
            if (m_phase_lines >= 4) {                // phased: the picture starts here, centred
                lpm_set(m_lpm_sum_rx / m_phase_lines);
                skip_phasing_to_image_save();
                m_img_sample = (int)(1.025 * m_smpl_per_lin); // half of the phasing line's white band
                double tmp_pos = fmod(m_img_sample, m_smpl_per_lin) / m_smpl_per_lin;
                m_last_col = (int)(tmp_pos * m_img_width);
                m_fax_pix_num = m_last_col;          // the picture starts at that column
            }
            m_curr_phase_len = 0;
        } else if (m_rx_state == RXPHASING && m_phase_lines > 0 && ++m_num_phase_lines >= 5) {
            skip_phasing_to_image(true);             // the phasing header ended without a centre
        } else if (m_curr_phase_len > 5 * SAMPLE_RATE) {
            m_curr_phase_len = m_curr_phase_high = m_curr_phase_low = 0;
        }
        m_curr_phase_len = m_curr_phase_high = m_curr_phase_low = 0;
    } else if (m_rx_state == RXPHASING) {            // no phasing lines yet: after 20 lines, ask the correlation
        double smpl_per_lin = lpm_to_samples(m_default_lpm);
        int smpl_per_lin_int = (int)smpl_per_lin;
        int nb_tested = (int)(m_phasing_calls_nb / smpl_per_lin);
        if (m_phase_lines == 0 && m_num_phase_lines == 0 && nb_tested >= 20 && (m_phasing_calls_nb % smpl_per_lin_int) == 0) {
            if (sig == RXIMAGE) skip_phasing_to_image(true);
            else if (sig == RXAPTSTOP) { end_rx(); skip_apt_rx(); }
        }
    }
}

void WefaxRx::put_pixel(int val)                     // (fldigi wefax_pic::update_rx_pic_bw)
{
    if ((long)m_pixels.size() <= m_fax_pix_num) m_pixels.resize(m_fax_pix_num + 1, 255);
    m_pixels[m_fax_pix_num] = (unsigned char)val;
}

bool WefaxRx::decode_image(int x)                    // fldigi decode_image
{
    double current_row_dbl = m_img_sample / m_smpl_per_lin;
    int current_row = (int)current_row_dbl;
    int curr_col = (int)(m_img_width * (current_row_dbl - current_row));
    if (curr_col == m_last_col) { m_pixel_val += x; m_pix_samples_nb++; }
    else {
        if (m_pix_samples_nb > 0) {                  // a pixel: the average of its samples
            m_pixel_val /= m_pix_samples_nb;
            put_pixel(m_pixel_val);
            m_hist[std::min(std::max(m_pixel_val, 0), 255)]++;
            m_fax_pix_num++;
        }
        m_last_col = curr_col; m_pixel_val = x; m_pix_samples_nb = 1;
    }
    if (current_row >= MAX_ROWS) { save_automatic("max"); return true; } // (fldigi: the most rows)
    return false;
}

void WefaxRx::skip_apt_rx()                          // fldigi skip_apt_rx
{
    lpm_set(0);
    m_rx_state = RXPHASING;
    reset_phasing_counters();
    m_img_sample = 0;
    m_imag_corr_max = 0.0; m_imag_corr_min = 1.0;
    std::fill(m_hist.begin(), m_hist.end(), 0);
    m_pixels.clear();                                // (fldigi wefax_pic::skip_rx_apt)
}

void WefaxRx::skip_phasing_to_image_save()           // fldigi skip_phasing_to_image_save
{
    if (m_rx_state == RXIMAGE) { save_automatic("phasing"); skip_apt_rx(); } // phasing found while reading a picture
    reset_phasing_counters();
    skip_phasing_to_image(false);
}

void WefaxRx::skip_phasing_to_image(bool)            // fldigi skip_phasing_to_image
{
    m_rx_state = RXIMAGE;
    m_pixels.clear();                                // (fldigi wefax_pic::skip_rx_phasing: a new picture)
    reset_counters();
    lpm_set(m_default_lpm);                          // (fldigi: the default LPM; its rounding is commented out)
}

void WefaxRx::end_rx()                               // fldigi end_rx
{
    m_rx_state = RXAPTSTART;
    reset_counters();
    m_pixels.clear();                                // (fldigi wefax_pic::abort_rx_viewer)
}

void WefaxRx::start_now()                            // fldigi's buttons: Skip APT, then Skip phasing
{
    if (m_rx_state == RXIMAGE) save_automatic("manual");
    skip_apt_rx();
    skip_phasing_to_image(true);
    m_img_sample = 0;                                // (skip_phasing_rx)
}

void WefaxRx::stop_now() { if (m_rx_state == RXIMAGE) save_automatic("manual"); else end_rx(); }

void WefaxRx::save_automatic(const char *extra_msg)  // fldigi save_automatic: is the picture worth keeping?
{
    long sum = 0, sum_vals = 0;                      // (fldigi statistics::calc)
    for (int i = 0; i < 256; i++) { sum_vals += (long)i * m_hist[i]; sum += m_hist[i]; }
    double avg = sum ? (double)(sum_vals / sum) : 0.0, dev = 0;
    if (sum) { double d2 = 0; for (int i = 0; i < 256; i++) { double v = i - avg; d2 += v * v * m_hist[i]; } dev = sqrt(d2 / sum); }
    int current_row = m_smpl_per_lin > 0 ? (int)(m_img_sample / m_smpl_per_lin) : 0;
    bool keep = true;
    if (m_fax_pix_num * 3 < 150000) keep = false;    // too small (fldigi counted 3 bytes a pixel)
    else if (strncmp(extra_msg, GARBAGE_STR, strlen(GARBAGE_STR)) == 0) keep = false;
    else if (m_imag_corr_max < 0.20) keep = false;   // the correlation was always low
    else if (((avg > 220) && (m_imag_corr_max < 0.30) && (dev < 30)) || ((avg > 240) && (m_imag_corr_max < 0.35) && (dev < 30))
          || ((avg > 230) && (m_imag_corr_max < 0.25) && (dev < 35)) || ((avg > 220) && (dev < 20))) keep = false; // blank
    else if ((avg < 100) && (m_imag_corr_max < 0.30)) keep = false; // dark interference
    else if ((avg < 80) && (m_imag_corr_max < 0.35)) keep = false;
    else if ((avg > 235) && (m_imag_corr_max < 0.65) && (dev < 40) && (current_row < 100)) keep = false; // small and white
    else if ((avg < 130) && (m_imag_corr_max < 0.70) && (dev < 100) && (current_row < 100)) keep = false; // cut between APT and phasing
    if (keep) {                                      // (fldigi wefax_pic::save_image): whole rows, handed over
        int rows = (int)(m_pixels.size() / m_img_width);
        m_done.assign(m_pixels.begin(), m_pixels.begin() + (size_t)rows * m_img_width); m_done_rows = rows; m_have_done = true;
    }
    end_rx();
}

bool WefaxRx::take_finished(std::vector<unsigned char> &px, int &w, int &h)
{
    if (!m_have_done) return false;
    px.swap(m_done); w = m_img_width; h = m_done_rows; m_have_done = false; m_done.clear();
    return true;
}

double WefaxRx::correlation_from_index(size_t line_length, size_t line_offset) const // fldigi correlation_from_index
{
    size_t end = line_length + m_img_sample, n = m_corr_buf.size();
    int avg_pred = 0, avg_curr = 0;
    for (size_t i = m_img_sample; i < end; ++i) { avg_pred += m_corr_buf[i % n]; avg_curr += m_corr_buf[(i + line_offset) % n]; }
    avg_pred /= (int)line_length; avg_curr /= (int)line_length;
    double numerator = 0, denom_pred = 0, denom_curr = 0; // (fldigi used int; double gives the same sums without overflow)
    for (size_t i = m_img_sample; i < end; ++i) {
        int dp = m_corr_buf[i % n] - avg_pred, dc = m_corr_buf[(i + line_offset) % n] - avg_curr;
        numerator += (double)dp * dc; denom_pred += (double)dp * dp; denom_curr += (double)dc * dc;
    }
    double denominator = sqrt(denom_pred * denom_curr);
    return denominator == 0.0 ? 0.0 : fabs(numerator / denominator);
}

void WefaxRx::correlation_calc()                     // fldigi correlation_calc
{
    ++m_corr_calls_nb;
    size_t corr_smpl_lin = lpm_to_samples(m_default_lpm);
    double current_corr = std::min(correlation_from_index(corr_smpl_lin, corr_smpl_lin), 1.0);
    if (m_corr_calls_nb < CORR_ROWS) { m_curr_corr_avg = current_corr; m_imag_corr_max = 0.0; m_imag_corr_min = 0.0; }
    else {
        m_curr_corr_avg = (m_curr_corr_avg * CORR_ROWS + current_corr) / (CORR_ROWS + 1);
        m_imag_corr_max = std::max(m_curr_corr_avg, m_imag_corr_max);
        m_imag_corr_min = std::min(m_curr_corr_avg, m_imag_corr_min);
    }
}

WefaxRx::State WefaxRx::correlation_state(const char **stop_code) // fldigi correlation_state
{
    switch (m_stable_state) {
    case RXAPTSTOP: *stop_code = "stable_stop"; break;
    case RXIMAGE: *stop_code = "stable_image"; break;
    default: *stop_code = "stable_idle"; break;
    }
    if (m_corr_calls_nb >= CORR_ROWS) {
        int crr_row = m_smpl_per_lin > 0 ? (int)(m_img_sample / m_smpl_per_lin) : 0;
        if (m_curr_corr_avg < CORRELATION) { *stop_code = "nocorr"; m_stable_state = RXAPTSTOP; }
        else if (m_curr_corr_avg > 3 * CORRELATION / 2) m_stable_state = RXIMAGE;
        else if ((m_imag_corr_max < 0.10) && (crr_row > 200)) { *stop_code = GARBAGE_STR ".200"; m_stable_state = RXAPTSTOP; }
        else if ((m_imag_corr_max < 0.20) && (crr_row > 500)) { *stop_code = GARBAGE_STR ".500"; m_stable_state = RXAPTSTOP; }
        else m_stable_state = IDLE;
    }
    return m_stable_state;
}

void WefaxRx::correlation_update(int the_sample)      // fldigi correlation_update
{
    size_t corr_smpl_lin = lpm_to_samples(m_default_lpm);
    if (m_corr_buf.size() != 2 * corr_smpl_lin) { m_corr_buf.assign(2 * corr_smpl_lin, 0); m_curr_corr_avg = 0.0; }
    if ((m_cnt_upd % corr_smpl_lin) == 0) correlation_calc();
    ++m_cnt_upd;
    m_corr_buf[m_img_sample % m_corr_buf.size()] = (unsigned char)the_sample;
}

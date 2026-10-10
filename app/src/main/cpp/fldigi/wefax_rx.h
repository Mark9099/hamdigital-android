// ----------------------------------------------------------------------------
// wefax_rx.h  --  weather fax (WEFAX / HF fax) receiver: fldigi's wefax modem (src/wefax/wefax.cxx, fldigi 4.1.23),
// receive only, without its picture window, waterfall-based signal tests and AFC, for HF Digital Modes (Android). The
// picture is kept here as grey pixels, a row at a time. See ANDROID_CHANGES.txt.
//
// Copyright (C) 2010
//		Remi Chateauneu, F4ECW
// (Receive-only edition 2026, HF Digital Modes)
//
// This is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License
// as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later
// version. It is distributed WITHOUT ANY WARRANTY; see the GNU General Public License (LICENSE).
// ----------------------------------------------------------------------------
#pragma once
#include <string>                                    // state names
#include <vector>                                    // the picture

class WefaxRx {
public:
    enum State { RXAPTSTART, RXAPTSTOP, RXPHASING, RXIMAGE, IDLE }; // (fldigi fax_state, receive part)
    static const int SAMPLE_RATE = 11025;             // fldigi's WEFAX sample rate

    explicit WefaxRx(int ioc = 576);                 // IOC 576 (APT start 300 Hz, 120 lpm) or 288 (675 Hz, 60 lpm)
    ~WefaxRx();
    WefaxRx(const WefaxRx &) = delete; WefaxRx &operator=(const WefaxRx &) = delete;
    void set_carrier(double f);                      // the centre (fldigi: 1900 Hz)
    double carrier() const { return m_carrier; }
    void set_shift(int hz);                          // the FM shift (800 Hz standard; DWD 850)
    void set_filter(int i) { m_filter = i < 0 || i > 2 ? 0 : i; } // input filter: 0 narrow, 1 medium, 2 wide (ACfax)
    void rx_process(const double *buf, int len);     // audio in (11025 Hz)
    void start_now();                                // skip APT and phasing: start a picture now (fldigi's Skip APT + Skip phasing)
    void stop_now();                                 // end the picture now (kept if worth it)
    void set_lpm(int lpm) { lpm_set(lpm); }          // lines per minute (normally from phasing; 120 or 60)

    State state() const { return m_rx_state; }
    int width() const { return m_img_width; }        // pixels a row (IOC x pi: 1809 for 576)
    int rows() const { return (int)(m_pixels.size() / m_img_width); } // rows of the picture so far (complete or not)
    const std::vector<unsigned char> &pixels() const { return m_pixels; } // grey 0..255, row after row
    double lpm() const { return m_lpm_img; }
    double correlation() const { return m_curr_corr_avg; } // line-to-line correlation (0..1): fldigi's signal metric
    int apt_freq() const { return m_last_apt_freq; } // the last half-second's black-white transition rate (Hz)
    /** A picture has ended (APT stop, a new start, too many rows, or stop_now) and was worth keeping: its pixels,
     *  width and rows are handed over (true once). */
    bool take_finished(std::vector<unsigned char> &px, int &w, int &h);

private:
    void lpm_set(double lpm) { m_lpm_img = lpm; m_smpl_per_lin = lpm > 0 ? SAMPLE_RATE * 60.0 / lpm : 0; } // (exact: fldigi rounded it to whole samples - 5512 for 5512.5 at 120 lpm - which slanted the picture half a sample a line)
    int lpm_to_samples(double lpm) const { return (int)(SAMPLE_RATE * 60.0 / lpm); }
    void reset_counters() { m_last_col = 0; m_fax_pix_num = 0; m_img_sample = 0; m_pix_samples_nb = 0; }
    void reset_phasing_counters();
    void decode(const int *buf, int nb);
    void decode_apt(int x, State sig);
    void decode_phasing(int x, State sig);
    bool decode_image(int x);
    void skip_apt_rx();
    void skip_phasing_to_image_save();
    void skip_phasing_to_image(bool auto_center);
    void end_rx();
    void save_automatic(const char *extra_msg);
    State signal_state();                            // fldigi fax_signal, the correlation part
    double correlation_from_index(size_t line_length, size_t line_offset) const;
    void correlation_calc();
    State correlation_state(const char **stop_code);
    void correlation_update(int the_sample);
    void put_pixel(int val);                         // (fldigi: wefax_pic::update_rx_pic_bw)

    State m_rx_state = IDLE;
    int m_current_value = 0;
    bool m_apt_high = false;
    int m_apt_trans = 0, m_apt_count = 0;
    int m_apt_start_freq, m_apt_stop_freq = 450;
    int m_last_apt_freq = 0, m_cr_1 = 0, m_cr_2 = 0, m_cr_3 = 0; // (fldigi: static curr / cr_1 / cr_2 / cr_3_freq)
    bool m_phase_high = false;
    int m_curr_phase_len = 0, m_curr_phase_high = 0, m_curr_phase_low = 0;
    int m_phase_lines = 0, m_num_phase_lines = 0, m_phasing_calls_nb = 0;
    double m_lpm_img = 0, m_lpm_sum_rx = 0;
    int m_default_lpm;
    int m_img_width;
    int m_img_sample = 0, m_last_col = 0, m_pixel_val = 0, m_pix_samples_nb = 0;
    long m_fax_pix_num = 0;                          // (fldigi counts bytes of RGB; here pixels)
    double m_smpl_per_lin = 0;
    double deviation_ratio = 0;
    double m_carrier = 1900;
    int m_filter = 0;
    double m_phase = 0;                              // (fldigi uses sine / cosine tables; here the NCO phase)
    int phasing_history[16] = {0}; size_t phasing_count = 0; // (fldigi: statics in decode_phasing)
    int m_sig_cnt = 0; State m_sig_state = IDLE; const char *m_sig_stop = ""; // (fldigi fax_signal: recomputed every 1000 samples)
    std::vector<unsigned char> m_corr_buf; size_t m_cnt_upd = 0; State m_stable_state = IDLE;
    double m_curr_corr_avg = 0, m_imag_corr_max = 0, m_imag_corr_min = 1; int m_corr_calls_nb = 0;
    std::vector<int> m_hist;                         // grey histogram of the picture (fldigi statistics)
    std::vector<unsigned char> m_pixels;             // the picture being received
    std::vector<unsigned char> m_done; int m_done_rows = 0; bool m_have_done = false; // a finished picture
    void *m_fir;                                     // the input filters (C_FIR_filter[3], wefax_rx.cxx)
    struct { double re, im; } prevz = {1, 0};        // (fldigi prevz)
};

/* RADE V2 left out of this build: V2 is pre-release and not for on-air use (rade_c README), and its weights add 62 MB of
   source. rade_api.c still refers to the V2 functions (behind the RADE_MODE_V2 flag, which this app never sets); these
   stand-ins satisfy the linker and refuse V2 if it is ever asked for. */
#include "rade_tx_v2.h"                               /* the V2 prototypes and state types */
#include "rade_rx_v2.h"

int rade_tx_v2_init(rade_tx_v2_state *tx, int bpf_en) { (void)tx; (void)bpf_en; return -1; } /* V2 not built: rade_open fails */
int rade_tx_v2_n_features_in(void) { return 0; }                                          /* (never reached) */
int rade_tx_v2_n_samples_out(void) { return 0; }
int rade_tx_v2_n_eoo_out(void) { return 0; }
int rade_tx_v2_process(rade_tx_v2_state *tx, RADE_COMP *o, const float *f) { (void)tx; (void)o; (void)f; return 0; }
int rade_tx_v2_eoo(rade_tx_v2_state *tx, RADE_COMP *o) { (void)tx; (void)o; return 0; }
void rade_tx_v2_set_data_symbol(rade_tx_v2_state *tx, float s) { (void)tx; (void)s; }
int rade_rx_v2_init(rade_rx_v2_state *rx, int bpf_en) { (void)rx; (void)bpf_en; return -1; }
int rade_rx_v2_nin(const rade_rx_v2_state *rx) { (void)rx; return 0; }
int rade_rx_v2_nin_max(void) { return 0; }
int rade_rx_v2_n_features_out(void) { return 0; }
int rade_rx_v2_process(rade_rx_v2_state *rx, float *f, const RADE_COMP *i) { (void)rx; (void)f; (void)i; return 0; }
float rade_rx_v2_get_data_symbol(const rade_rx_v2_state *rx) { (void)rx; return 0; }

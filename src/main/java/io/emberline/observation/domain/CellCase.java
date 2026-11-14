package io.emberline.observation.domain;

/**
 * Data cases of a cell row on the read path (00-decisions E2/E3/E4). One handler class per case, chosen by a
 * registry — no switch. Values are always the file's (A1); the case only decides the flags.
 */
public enum CellCase {
    /** value null (missing month 2001-06, status missing): value stays null, never 0 (E2). */
    VALUE_MISSING,
    /** value present, lo/hi null (modis_m2v_terra rows, meta cell_interval): interval_unavailable. */
    INTERVAL_UNAVAILABLE,
    /** value outside [value_lo, value_hi] (441,269 modis_m2v rows): kept, interval_out_of_range (E3). */
    VALUE_OUTSIDE_INTERVAL,
    /** value inside its interval. */
    REGULAR
}

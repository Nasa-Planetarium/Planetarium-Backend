package io.emberline.shared.release.load.cases;

/**
 * Data cases of the release files (00-decisions E1–E4). Each case has its own handler class; the registry picks it.
 * Loaders count rows per case with one SQL scan whose result columns are named like the constants (lower case).
 */
public enum DataCase {
    /** status=missing and value NULL (2001-06): kept NULL and served as missing, never 0 (E2). */
    MISSING_MONTH_NULL_VALUE,
    /** status=missing but a value is present: kept as the file has it (A1), noted. */
    MISSING_MONTH_WITH_VALUE,
    /** value NULL while status is not missing: the file contradicts itself → reject. */
    NULL_VALUE_NOT_MISSING,
    /** value outside [value_lo, value_hi] (441,269 cell rows): value kept, the read side does not use that interval (E3). */
    VALUE_OUTSIDE_INTERVAL,
    /** value_lo greater than value_hi → reject. */
    INTERVAL_INVERTED,
    /**
     * a negative value or bound (v1.2 static-source removal leaves some cells slightly below 0, release note "draw as 0"):
     * kept as the file has it (A1), noted.
     */
    NEGATIVE_VALUE,
    /** a negative raw count (raw_modis, raw_viirs, n_cells) → reject. */
    NEGATIVE_COUNT
}

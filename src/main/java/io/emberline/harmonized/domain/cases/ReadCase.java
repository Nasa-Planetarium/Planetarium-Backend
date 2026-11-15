package io.emberline.harmonized.domain.cases;

/**
 * Data cases met when serving release values. One handler class per case, chosen by
 * {@link ReadCaseRegistry} (a map, no switch). Applied in declaration order. Notes: docs/cases/*.md.
 */
public enum ReadCase {
    /** status missing → value, value_lo, value_hi null (never 0), status stays missing. docs/cases/missing_month.md */
    MISSING_MONTH,
    /** v1.1: status partial → value is month-to-date, value_projected/as_of_date passed, verdict uncertain. docs/cases/partial_month.md */
    PARTIAL_MONTH,
    /** value outside [value_lo, value_hi] → value and interval kept as stored, flag interval_out_of_range. docs/cases/interval_out_of_range.md */
    INTERVAL_OUT_OF_RANGE,
    /** Terra-only period: value present, value_lo/value_hi null → kept null, flag interval_unavailable. docs/cases/interval_unavailable.md */
    INTERVAL_UNAVAILABLE,
    /** low_sample=true → the stored verdict is served as-is, never recomputed. docs/cases/low_sample.md */
    LOW_SAMPLE
}

package io.emberline.harmonized.domain.cases;

import io.emberline.harmonized.domain.Reading;

/**
 * E3 「구간밖은 취급 안해야지」: the value is the model's and stays; the interval it falls outside of is not served
 * (value_lo/value_hi null) and the reading carries {@code interval_out_of_range}. A relative tolerance of 1e-9 keeps
 * floating-point summation noise (e.g. Σ value vs Σ value_hi of equal cells) from raising the flag.
 */
public final class IntervalOutOfRangeHandler implements ReadCaseHandler {

    public static final String FLAG = "interval_out_of_range";
    static final double TOLERANCE = 1e-9;

    @Override
    public ReadCase handles() {
        return ReadCase.INTERVAL_OUT_OF_RANGE;
    }

    @Override
    public boolean matches(Reading r) {
        if (r.value() == null || r.valueLo() == null || r.valueHi() == null) {
            return false;
        }
        double slack = TOLERANCE * Math.max(1.0, Math.abs(r.value()));
        return r.value() < r.valueLo() - slack || r.value() > r.valueHi() + slack;
    }

    @Override
    public Reading apply(Reading reading) {
        return reading.withInterval(null, null).flagged(FLAG);
    }
}

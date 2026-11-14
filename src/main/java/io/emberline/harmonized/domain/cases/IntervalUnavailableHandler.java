package io.emberline.harmonized.domain.cases;

import io.emberline.harmonized.domain.Reading;

/**
 * Terra-only months (2000-11 – 2002-06) have no cell- or point-level interval: a value with a null bound keeps both
 * bounds null (never filled from the value) and carries {@code interval_unavailable}.
 */
public final class IntervalUnavailableHandler implements ReadCaseHandler {

    public static final String FLAG = "interval_unavailable";

    @Override
    public ReadCase handles() {
        return ReadCase.INTERVAL_UNAVAILABLE;
    }

    @Override
    public boolean matches(Reading r) {
        return r.value() != null && (r.valueLo() == null || r.valueHi() == null)
                && !r.flags().contains(IntervalOutOfRangeHandler.FLAG);
    }

    @Override
    public Reading apply(Reading reading) {
        return reading.withInterval(null, null).flagged(FLAG);
    }
}

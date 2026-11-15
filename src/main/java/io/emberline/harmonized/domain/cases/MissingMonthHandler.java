package io.emberline.harmonized.domain.cases;

import io.emberline.harmonized.domain.Reading;
import io.emberline.harmonized.domain.Status;

/** 「null 은 우리가 해결한다」: a missing month is served as missing with null numbers, never painted as 0. */
public final class MissingMonthHandler implements ReadCaseHandler {

    @Override
    public ReadCase handles() {
        return ReadCase.MISSING_MONTH;
    }

    @Override
    public boolean matches(Reading reading) {
        return Status.MISSING.equals(reading.status());
    }

    @Override
    public Reading apply(Reading reading) {
        return reading.withValues(null, null, null);
    }
}

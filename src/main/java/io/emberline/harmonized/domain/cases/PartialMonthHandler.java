package io.emberline.harmonized.domain.cases;

import io.emberline.harmonized.domain.Reading;
import io.emberline.harmonized.domain.Status;

/**
 * v1.1 month in progress (team api_contract.md): value is the month-to-date total, value_projected and as_of_date are
 * passed through as stored, and a verdict (where one is served) is always uncertain.
 */
public final class PartialMonthHandler implements ReadCaseHandler {

    @Override
    public ReadCase handles() {
        return ReadCase.PARTIAL_MONTH;
    }

    @Override
    public boolean matches(Reading reading) {
        return Status.PARTIAL.equals(reading.status());
    }

    @Override
    public Reading apply(Reading reading) {
        return reading.verdict() == null ? reading : reading.withVerdict(Status.UNCERTAIN, reading.lowSample());
    }
}

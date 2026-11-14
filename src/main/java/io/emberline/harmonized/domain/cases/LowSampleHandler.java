package io.emberline.harmonized.domain.cases;

import io.emberline.harmonized.domain.Reading;

/**
 * low_sample=true (baseline_p75 &lt; 1): the release's verdict is served unchanged — also when it is {@code above}
 * with a percentile under 80 (team rule: low_sample and value ≥ 3 → above). Never recomputed (A1, E5).
 */
public final class LowSampleHandler implements ReadCaseHandler {

    @Override
    public ReadCase handles() {
        return ReadCase.LOW_SAMPLE;
    }

    @Override
    public boolean matches(Reading reading) {
        return Boolean.TRUE.equals(reading.lowSample());
    }

    @Override
    public Reading apply(Reading reading) {
        return reading;
    }
}

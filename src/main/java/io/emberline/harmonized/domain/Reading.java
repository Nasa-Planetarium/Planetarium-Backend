package io.emberline.harmonized.domain;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * A value as it will be served: value, interval, month-to-date projection, status, stored verdict and the flags the
 * data cases added. Immutable; the case handlers return changed copies.
 */
public record Reading(Double value, Double valueLo, Double valueHi, Double valueProjected, LocalDate asOfDate,
                      String status, String verdict, Boolean lowSample, List<String> flags) {

    public Reading {
        flags = List.copyOf(flags);
    }

    public static Reading of(Double value, Double valueLo, Double valueHi, String status) {
        return new Reading(value, valueLo, valueHi, null, null, status, null, null, List.of());
    }

    /** No row in the release for this month or day: response-only status out_of_range, every value null. */
    public static Reading outOfRange() {
        return of(null, null, null, Status.OUT_OF_RANGE);
    }

    public Reading withProjection(Double projected, LocalDate asOf) {
        return new Reading(value, valueLo, valueHi, projected, asOf, status, verdict, lowSample, flags);
    }

    public Reading withVerdict(String storedVerdict, Boolean storedLowSample) {
        return new Reading(value, valueLo, valueHi, valueProjected, asOfDate, status, storedVerdict, storedLowSample, flags);
    }

    public Reading withValues(Double v, Double lo, Double hi) {
        return new Reading(v, lo, hi, valueProjected, asOfDate, status, verdict, lowSample, flags);
    }

    public Reading withInterval(Double lo, Double hi) {
        return new Reading(value, lo, hi, valueProjected, asOfDate, status, verdict, lowSample, flags);
    }

    public Reading withStatus(String s) {
        return new Reading(value, valueLo, valueHi, valueProjected, asOfDate, s, verdict, lowSample, flags);
    }

    public Reading flagged(String flag) {
        List<String> all = new ArrayList<>(flags);
        if (!all.contains(flag)) {
            all.add(flag);
        }
        return new Reading(value, valueLo, valueHi, valueProjected, asOfDate, status, verdict, lowSample, all);
    }
}

package io.emberline.harmonized.domain;

import java.time.LocalDate;

/**
 * One AOI month as the release has it: the {@code aoi_month} row and, when present, its {@code aoi_month_compare} row
 * (fields prefixed {@code compare}, plus its v1.2 r3 baseline_period / continuity_ratio / continuity_source). Nothing here
 * is computed; NULL stays null.
 */
public record MonthRecord(int year, int month, Double value, Double valueLo, Double valueHi, Double valueProjected,
                          LocalDate asOfDate, Long rawModis, Long rawViirs, String valueSource, String dataSource,
                          String status, Integer nCells, Double compareValue, Double baselineMedian, Double baselineP25,
                          Double baselineP75, Double percentile, Double ratioToMedian, Integer nBaselineYears,
                          String verdict, String compareStatus, Boolean lowSample, Double compareValueProjected,
                          LocalDate compareAsOfDate, String baselinePeriod, Double continuityRatio,
                          String continuitySource) {

    /** The month as a reading (value, interval, status, stored verdict) before the data cases are applied. */
    public Reading reading() {
        return Reading.of(value, valueLo, valueHi, status).withProjection(valueProjected, asOfDate)
                .withVerdict(verdict, lowSample);
    }

    /** The comparison row's own value and interval of the month (value from aoi_month_compare as-is). */
    public Reading compareReading() {
        return Reading.of(compareValue, valueLo, valueHi, status)
                .withProjection(compareValueProjected != null ? compareValueProjected : valueProjected,
                        compareAsOfDate != null ? compareAsOfDate : asOfDate)
                .withVerdict(verdict, lowSample);
    }
}

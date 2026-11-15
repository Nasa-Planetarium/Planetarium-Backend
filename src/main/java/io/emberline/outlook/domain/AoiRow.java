package io.emberline.outlook.domain;

/**
 * One row of aoi_occurrence_nextmonth_latest as stored (a Korean municipality, {@code SGG_*}); n_years is the file's
 * float as an integer (the loader refuses a non-integral value).
 */
public record AoiRow(String aoiId, String targetMonth, double pAny, Double pNone, Double baselineP, String model,
                     String asOfDate, boolean suspectedIndustrial, Double rate5y, Integer nYears, Boolean lag1Projected) {

    /** Null when the file has no baseline for the row. */
    public VsNormal vsNormal() {
        return baselineP == null ? null : VsNormal.of(pAny, baselineP);
    }
}

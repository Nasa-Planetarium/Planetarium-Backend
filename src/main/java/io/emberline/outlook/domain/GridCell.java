package io.emberline.outlook.domain;

/**
 * One row of grid_occurrence_nextmonth_latest as stored: 0.5° cell id, centre, probabilities and flags. Nullable
 * columns are boxed.
 */
public record GridCell(long cellId, double lat, double lon, double pAny, Double pNone, double baselineP,
                       boolean persistentSource, String adm0, String continent, Double rate5y, Boolean lag1Projected) {

    public VsNormal vsNormal() {
        return VsNormal.of(pAny, baselineP);
    }
}

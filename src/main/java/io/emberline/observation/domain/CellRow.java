package io.emberline.observation.domain;

import java.time.LocalDate;

/**
 * One row of observation.cell_month as loaded (values copied from harmonized_cell_month.parquet, A1).
 * {@code cellLat}/{@code cellLon} are the file's float32 centers. {@code valueProjected}/{@code asOfDate} are set on
 * partial (month in progress) rows of a v1.1 release only.
 */
public record CellRow(long cellId, float cellLat, float cellLon, Double value, Double valueLo, Double valueHi,
                      Integer rawModis, Integer rawViirs, String valueSource, String dataSource, String status,
                      Double valueProjected, LocalDate asOfDate) {

    public static final String PARTIAL = "partial";

    public boolean partial() {
        return PARTIAL.equals(status);
    }

    public boolean intervalPresent() {
        return valueLo != null && valueHi != null;
    }
}

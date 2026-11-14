package io.emberline.observation.domain;

/**
 * One row of observation.cell_month as loaded (values copied from harmonized_cell_month.parquet, A1).
 * {@code cellLat}/{@code cellLon} are the file's float32 centers.
 */
public record CellRow(long cellId, float cellLat, float cellLon, Double value, Double valueLo, Double valueHi,
                      Integer rawModis, Integer rawViirs, String valueSource, String dataSource, String status) {

    public boolean intervalPresent() {
        return valueLo != null && valueHi != null;
    }
}

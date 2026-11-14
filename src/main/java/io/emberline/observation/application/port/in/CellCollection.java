package io.emberline.observation.application.port.in;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.emberline.shared.json.GeoJson;
import java.util.List;

/**
 * GET /api/v1/cells — FeatureCollection of 0.1° cells of one month (contract CellCollection). A cell without a
 * feature is 0; whether the month itself is missing is {@code properties.month_status}.
 */
public record CellCollection(String type, Properties properties, List<Feature> features) implements GeoJson {

    public static final String ZERO_RULE = "a cell without a feature is 0 (see month_status for missing)";

    public record Properties(@JsonInclude(JsonInclude.Include.NON_NULL) String aoiId,
                             @JsonInclude(JsonInclude.Include.NON_NULL) List<Double> bbox,
                             int year, int month, String unit, double cellSizeDeg, int nCells, String monthStatus,
                             String zeroRule, String methodVersion) {
    }

    public record Feature(String type, long id, Polygon geometry, CellProperties properties) {
    }

    public record Polygon(String type, List<double[][]> coordinates) {
    }

    /**
     * Columns of harmonized_cell_month as the file has them, plus the read-path flags (E3): interval_out_of_range
     * when value lies outside [value_lo, value_hi], interval_unavailable when the row has no interval. The eight
     * fire-event columns are not in the release and are null.
     */
    public record CellProperties(long cellId, double cellLat, double cellLon, Double value, Double valueLo,
                                 Double valueHi, Integer rawModis, Integer rawViirs, String valueSource,
                                 String dataSource, String status, boolean intervalOutOfRange,
                                 boolean intervalUnavailable, Integer nEventsViirs, Double eventAreaMaxKm2Viirs,
                                 Integer eventDaysMaxViirs, Double eventAreaShareMaxViirs, Integer nEventsModis,
                                 Double eventAreaMaxKm2Modis, Integer eventDaysMaxModis, Double eventAreaShareMaxModis) {
    }
}

package io.emberline.observation.application.port.in;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.emberline.shared.json.Coverage;
import java.util.List;

/**
 * GET /api/v1/cells/frp (contract {@code CellFrpList}): FRP of 0.1° cells per day (rows as stored) or per month (cell ×
 * month groups). {@code aoi_id} / {@code bbox} echo the area asked; {@code data_release} is the loaded release tag.
 */
public record CellFrpList(@JsonInclude(JsonInclude.Include.NON_NULL) String aoiId,
                          @JsonInclude(JsonInclude.Include.NON_NULL) List<Double> bbox, String start, String end,
                          String freq, int count, List<Cell> cells, Coverage coverage, String notice, String dataRelease) {

    /**
     * Contract {@code CellFrp}: the file's values ({@code -0.0} written as 0), {@code cell_lat/lon} = the cell center
     * from the id (the file has no coordinates), {@code date} = date_local or the month's first day.
     */
    public record Cell(long cellId, double cellLat, double cellLon, String date, Double frpSum, Double frpSnppEquivSum,
                       Float frpSnppEquivMax, long nPoints) {
    }
}

package io.emberline.fireinfo.application.port.in;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.emberline.shared.json.Coverage;
import java.util.List;

/**
 * GET /api/v1/spread-nowcast (contract schema SpreadNowcast): the file head, the cells of the area at or above
 * {@code min_level}, coverage and the notice (always). In range without candidate cells: {@code cells: []} with
 * {@code coverage.available=true}; out of range: {@code cells: []} with {@code available=false}.
 */
public record SpreadNowcastResponse(String asOfDate, String targetDate, String model, String sourceSatellite,
                                    boolean weatherAvailable, Integer weatherLagDays,
                                    @JsonInclude(JsonInclude.Include.NON_NULL) String aoiId,
                                    @JsonInclude(JsonInclude.Include.NON_NULL) List<Double> bbox,
                                    int count, List<SpreadCellItem> cells, Coverage coverage, String notice,
                                    String dataRelease) {

    public SpreadNowcastResponse {
        bbox = bbox == null ? null : List.copyOf(bbox);
        cells = List.copyOf(cells);
    }
}

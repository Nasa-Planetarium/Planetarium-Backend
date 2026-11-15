package io.emberline.outlook.application.port.in;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.emberline.shared.json.Coverage;
import java.util.List;

/** Contract {@code GridOccurrence}; {@code bbox} only when the request had one. */
public record GridOccurrenceResponse(String targetMonth, String asOfDate, String model, double cellSizeDeg,
                                     @JsonInclude(JsonInclude.Include.NON_NULL) List<Double> bbox, int count,
                                     List<GridCellView> cells, Coverage coverage, String notice, String dataRelease) {
}

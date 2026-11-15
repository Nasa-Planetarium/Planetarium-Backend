package io.emberline.outlook.application.port.in;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.emberline.shared.json.Coverage;

/**
 * Contract {@code AoiOccurrence} for one AOI (no {@code rank}: list responses only). {@code unit=aoi}: the file row of
 * a municipality; {@code unit=grid_summary}: every value null and {@code grid_summary} set (or null with
 * {@code coverage.available=false}).
 */
public record AoiOccurrenceResponse(String aoiId, String nameKo, String unit, String targetMonth, String asOfDate,
                                    String model, Double pAny, Double pNone, Double baselineP, String vsNormal,
                                    Boolean suspectedIndustrial, @JsonProperty("rate_5y") Double rate5y, Integer nYears,
                                    Boolean lag1Projected, GridSummary gridSummary, Coverage coverage, String notice,
                                    String dataRelease) {
}

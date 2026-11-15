package io.emberline.outlook.application.port.in;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.emberline.shared.json.Coverage;

/** Contract {@code AoiOccurrence} as an item of {@code AoiOccurrenceList}: always {@code unit=aoi}, with {@code rank} (null for suspected rows). */
public record AoiOccurrenceItem(String aoiId, String nameKo, String unit, String targetMonth, String asOfDate,
                                String model, double pAny, Double pNone, Double baselineP, String vsNormal,
                                boolean suspectedIndustrial, Integer rank, @JsonProperty("rate_5y") Double rate5y,
                                Integer nYears, Boolean lag1Projected, GridSummary gridSummary, Coverage coverage,
                                String notice, String dataRelease) {
}

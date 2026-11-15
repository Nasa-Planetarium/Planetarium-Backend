package io.emberline.harmonized.application.port.in;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Comparison body (contract schema Comparison): value … verdict and low_sample are the aoi_month_compare row as stored;
 * status, value_source and the interval come from aoi_month. v1.1 adds value_projected / as_of_date; v1.2 r3 adds
 * baseline_period, continuity_ratio and continuity_source (the compare row as stored).
 */
public record ComparisonResponse(String aoiId, int year, int month, String unit, Double value, Double baselineMedian,
                                 Double baselineP25, Double baselineP75, Double percentile, Double ratioToMedian,
                                 Integer nBaselineYears, String verdict, String status, String valueSource,
                                 Double valueLo, Double valueHi, Double valueProjected, LocalDate asOfDate,
                                 Boolean lowSample, String baselinePeriod, Double continuityRatio,
                                 String continuitySource, List<String> flags, List<BaselineYear> baseline,
                                 Map<String, Object> rule, String methodVersion) {
}

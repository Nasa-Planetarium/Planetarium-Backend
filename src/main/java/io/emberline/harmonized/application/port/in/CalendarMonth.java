package io.emberline.harmonized.application.port.in;

import java.time.LocalDate;
import java.util.List;

/**
 * One calendar cell: aoi_month values (after the data cases) and the stored comparison verdict. v1.1 adds
 * value_projected and as_of_date (month in progress); {@code flags} names the data case applied (e.g.
 * interval_out_of_range).
 */
public record CalendarMonth(int year, int month, Double value, Double valueLo, Double valueHi, Double valueProjected,
                            LocalDate asOfDate, Long rawModis, Long rawViirs, String valueSource, String dataSource,
                            String status, Integer nCells, String verdict, Double percentile, Double ratioToMedian,
                            Boolean lowSample, List<String> missingDates, Integer daysCovered, List<String> flags) {
}

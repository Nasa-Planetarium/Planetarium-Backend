package io.emberline.harmonized.application.port.in;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDate;
import java.util.List;

/**
 * One summary row: a month ({@code date} = day 1, with year/month) or a day. basis: {@code aoi_month} (whole month,
 * stored row) or {@code cell_day} (Σ harmonized_cell_day over the AOI's cells; partial-range months add the month
 * residual pro rata and say {@code residual_prorated: true}).
 */
public record SummaryPoint(LocalDate date,
                           @JsonInclude(JsonInclude.Include.NON_NULL) Integer year,
                           @JsonInclude(JsonInclude.Include.NON_NULL) Integer month,
                           Double value, Double valueLo, Double valueHi,
                           @JsonInclude(JsonInclude.Include.NON_NULL) Double valueProjected,
                           @JsonInclude(JsonInclude.Include.NON_NULL) LocalDate asOfDate,
                           String valueSource, String dataSource, String status, List<String> missingDates,
                           List<String> flags, String basis,
                           @JsonInclude(JsonInclude.Include.NON_NULL) Boolean residualProrated) {
}

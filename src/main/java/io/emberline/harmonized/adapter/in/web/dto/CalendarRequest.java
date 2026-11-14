package io.emberline.harmonized.adapter.in.web.dto;

import io.emberline.harmonized.application.port.in.CalendarQuery;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.web.bind.annotation.BindParam;

/**
 * Query of GET /api/v1/aois/{aoiId}/calendar. Years 2000–2100 here; start ≤ end and the 40-year span are checked by the
 * service once the AOI's default years are known. {@code format} is applied by content negotiation (csv = text/csv).
 */
public record CalendarRequest(
        @Parameter(name = "start_year") @BindParam("start_year") @Min(2000) @Max(2100) Integer startYear,
        @Parameter(name = "end_year") @BindParam("end_year") @Min(2000) @Max(2100) Integer endYear,
        @Parameter(name = "format") @Pattern(regexp = "^(json|csv)$", message = "must be json or csv") String format) {

    public CalendarQuery toQuery() {
        return new CalendarQuery(startYear, endYear);
    }
}

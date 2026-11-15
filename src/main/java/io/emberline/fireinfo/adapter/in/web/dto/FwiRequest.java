package io.emberline.fireinfo.adapter.in.web.dto;

import io.emberline.fireinfo.application.port.in.FwiQuery;
import io.emberline.shared.validation.Patterns;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.constraints.Pattern;

/** Query of GET /api/v1/aois/{aoiId}/fwi: {@code date}, or {@code start} with {@code end} (rules in the service). */
public record FwiRequest(
        @Parameter(name = "date") @Pattern(regexp = Patterns.DATE, message = "must be YYYY-MM-DD") String date,
        @Parameter(name = "start") @Pattern(regexp = Patterns.DATE, message = "must be YYYY-MM-DD") String start,
        @Parameter(name = "end") @Pattern(regexp = Patterns.DATE, message = "must be YYYY-MM-DD") String end,
        @Parameter(name = "format") @Pattern(regexp = "^(json|csv)$", message = "must be json or csv") String format) {

    public FwiQuery toQuery() {
        return new FwiQuery(date, start, end);
    }
}

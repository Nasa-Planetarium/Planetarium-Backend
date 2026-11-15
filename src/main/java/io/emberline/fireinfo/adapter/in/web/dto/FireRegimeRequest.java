package io.emberline.fireinfo.adapter.in.web.dto;

import io.emberline.fireinfo.application.port.in.FireRegimeQuery;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.constraints.Pattern;

/** Query of GET /api/v1/aois/{aoiId}/fire-regime: {@code start}, {@code end} as YYYY-MM (contract schema YearMonth). */
public record FireRegimeRequest(
        @Parameter(name = "start") @Pattern(regexp = "^\\d{4}-(0[1-9]|1[0-2])$", message = "must be YYYY-MM") String start,
        @Parameter(name = "end") @Pattern(regexp = "^\\d{4}-(0[1-9]|1[0-2])$", message = "must be YYYY-MM") String end,
        @Parameter(name = "format") @Pattern(regexp = "^(json|csv)$", message = "must be json or csv") String format) {

    public FireRegimeQuery toQuery() {
        return new FireRegimeQuery(start, end);
    }
}

package io.emberline.harmonized.adapter.in.web.dto;

import io.emberline.harmonized.application.port.in.SummaryQuery;
import io.emberline.shared.error.ErrorCode;
import io.emberline.shared.error.InvalidRequestException;
import io.emberline.shared.validation.Patterns;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.DateTimeException;
import java.time.LocalDate;

/** Query of GET /api/v1/aois/{aoiId}/summary (dates are date_local, inclusive; freq month by default). */
public record SummaryRequest(
        @Parameter(name = "start") @NotNull @Pattern(regexp = Patterns.DATE, message = "must be YYYY-MM-DD") String start,
        @Parameter(name = "end") @NotNull @Pattern(regexp = Patterns.DATE, message = "must be YYYY-MM-DD") String end,
        @Parameter(name = "freq") @Pattern(regexp = "^(month|day)$", message = "must be month or day") String freq,
        @Parameter(name = "format") @Pattern(regexp = "^(json|csv)$", message = "must be json or csv") String format) {

    public SummaryQuery toQuery() {
        return new SummaryQuery(date(start, "start"), date(end, "end"), freq == null ? "month" : freq);
    }

    private static LocalDate date(String value, String param) {
        try {
            LocalDate d = LocalDate.parse(value);
            if (d.getYear() < 2000 || d.getYear() > 2100) {
                throw new InvalidRequestException(ErrorCode.INVALID_PARAM, param, param + " must be within 2000-2100");
            }
            return d;
        } catch (DateTimeException e) {
            throw new InvalidRequestException(ErrorCode.INVALID_PARAM, param, param + " is not a calendar date", e);
        }
    }
}

package io.emberline.provenance.adapter.in.web.dto;

import io.emberline.provenance.application.port.in.SatelliteStatusQuery;
import io.emberline.shared.error.ErrorCode;
import io.emberline.shared.error.InvalidRequestException;
import io.emberline.shared.validation.Patterns;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.constraints.Pattern;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/** Query of GET /api/v1/satellite-status: start/end as YYYY-MM-DD (calendar dates only). */
public record SatelliteStatusRequest(
        @Parameter(name = "start") @Pattern(regexp = Patterns.DATE, message = "must be YYYY-MM-DD") String start,
        @Parameter(name = "end") @Pattern(regexp = Patterns.DATE, message = "must be YYYY-MM-DD") String end) {

    public SatelliteStatusQuery toQuery() {
        return new SatelliteStatusQuery(date(start, "start"), date(end, "end"));
    }

    private static LocalDate date(String value, String param) {
        if (value == null) {
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw new InvalidRequestException(ErrorCode.INVALID_PARAM, param, param + " is not a calendar date");
        }
    }
}

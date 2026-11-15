package io.emberline.observation.adapter.in.web.dto;

import io.emberline.observation.application.port.in.CellFrpQuery;
import io.emberline.shared.error.ErrorCode;
import io.emberline.shared.error.InvalidRequestException;
import io.emberline.shared.validation.Patterns;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.DateTimeException;
import java.time.LocalDate;
import org.springframework.web.bind.annotation.BindParam;

/**
 * Query of GET /api/v1/cells/frp: exactly one of bbox / aoi_id (checked by the use case), start and end as YYYY-MM-DD
 * (date_local, inclusive), freq day (default) or month, format json or csv.
 */
public record CellFrpRequest(
        @Parameter(name = "bbox") @Size(max = 200) @Pattern(regexp = CellsRequest.BBOX, message = "must be west,south,east,north") String bbox,
        @Parameter(name = "aoi_id") @BindParam("aoi_id") @Pattern(regexp = Patterns.AOI_ID, message = "must be an aoi_id") String aoiId,
        @Parameter(name = "start", required = true) @NotNull(message = "is required") @Pattern(regexp = Patterns.DATE, message = "must be YYYY-MM-DD") String start,
        @Parameter(name = "end", required = true) @NotNull(message = "is required") @Pattern(regexp = Patterns.DATE, message = "must be YYYY-MM-DD") String end,
        @Parameter(name = "freq") @Pattern(regexp = "^(day|month)$", message = "must be day or month") String freq,
        @Parameter(name = "format") @Pattern(regexp = "^(json|csv)$", message = "must be json or csv") String format) {

    public CellFrpQuery toQuery() {
        return new CellFrpQuery(aoiId, bbox, date(start, "start"), date(end, "end"), freq == null ? "day" : freq);
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

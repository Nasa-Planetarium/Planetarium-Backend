package io.emberline.provenance.adapter.in.web.dto;

import io.emberline.provenance.application.port.in.SatelliteStatusQuery;
import io.emberline.shared.error.ErrorCode;
import io.emberline.shared.error.InvalidRequestException;
import io.emberline.shared.validation.Patterns;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Query of GET /api/v1/satellite-status: start/end as YYYY-MM-DD (UTC calendar dates), freq day (default) or month,
 * satellite = comma-separated file names (contract SatelliteDayStatus.satellite enum), absent = all.
 */
public record SatelliteStatusRequest(
        @Parameter(name = "start") @Pattern(regexp = Patterns.DATE, message = "must be YYYY-MM-DD") String start,
        @Parameter(name = "end") @Pattern(regexp = Patterns.DATE, message = "must be YYYY-MM-DD") String end,
        @Parameter(name = "freq") @Pattern(regexp = "^(day|month)$", message = "must be day or month") String freq,
        @Parameter(name = "satellite") @Size(max = 64, message = "is at most 64 characters") String satellite) {

    /** Contract SatelliteDayStatus.satellite: the five satellite names of the release files. */
    public static final Set<String> SATELLITES = Set.of("Terra", "Aqua", "S-NPP", "NOAA-20", "NOAA-21");

    public SatelliteStatusQuery toQuery() {
        return new SatelliteStatusQuery(date(start, "start"), date(end, "end"), freq == null ? "day" : freq, satellites());
    }

    private List<String> satellites() {
        List<String> out = new ArrayList<>();
        if (satellite == null) {
            return out;
        }
        for (String s : satellite.split(",", -1)) {
            String name = s.strip();
            if (!SATELLITES.contains(name)) {
                throw new InvalidRequestException(ErrorCode.INVALID_PARAM, "satellite",
                        "satellite is a comma-separated list of Terra, Aqua, S-NPP, NOAA-20, NOAA-21");
            }
            if (!out.contains(name)) {
                out.add(name);
            }
        }
        return out;
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

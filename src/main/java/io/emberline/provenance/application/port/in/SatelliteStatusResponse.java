package io.emberline.provenance.application.port.in;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;

/**
 * GET /api/v1/satellite-status. Without satellite_day_status in the release (v1-m2v-2026-11-14) the answer is month
 * level ({@code granularity: month}, {@code day_level: not_provided}) from the release's month status — see
 * docs/DEVIATIONS.md. {@code days} only appears when the release ships day-level status.
 */
public record SatelliteStatusResponse(String start, String end, String granularity, String dayLevel, String updatedAt,
                                      List<Satellite> satellites, List<Month> months,
                                      @JsonInclude(JsonInclude.Include.NON_NULL) List<Day> days, String methodVersion) {

    /** One satellite behind the months in range. */
    public record Satellite(String satellite, String instrument, String firstDate, String lastDate, Map<String, Integer> months) {
    }

    /** One month in range; out_of_range when the release has no row for it. */
    public record Month(String yearMonth, int year, int month, String valueSource, String dataSource, String status,
                        List<String> satellites) {
    }

    /** One satellite-day (only from satellite_day_status). */
    public record Day(String date, String satellite, String status, String dataSource) {
    }
}

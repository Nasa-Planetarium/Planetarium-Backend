package io.emberline.provenance.application.port.in;

import java.util.List;

/**
 * GET /api/v1/satellite-status (contract {@code SatelliteStatus}, v1.2): {@code freq=day} → {@code days[]} from
 * satellite_day_status (UTC dates), {@code freq=month} → {@code months[]} from satellite_month_status; the other array
 * is empty. {@code start}/{@code end} are the range applied (null for an open month range), {@code through} is the
 * file's last {@code date_utc}. Values and codes are the files' (satellite names, statuses, notes as stored).
 */
public record SatelliteStatusResponse(String start, String end, String freq, String through, List<Day> days,
                                      List<Month> months, String methodVersion, String dataRelease) {

    /** Contract {@code SatelliteDayStatus}: the served columns of satellite_day_status (MAPPING §8.2). */
    public record Day(String satellite, String dateUtc, String status, Long nDetGlobal, String coverage,
                      Double ratioToSeasonal, String note) {
    }

    /** Contract {@code SatelliteMonthStatus}: a satellite_month_status row. */
    public record Month(String satellite, int year, int month, Integer obsDays, Integer lowDays, Integer missingDays,
                        Integer daysInMonth, String firstDay, String lastDay, String monthStatus) {
    }
}

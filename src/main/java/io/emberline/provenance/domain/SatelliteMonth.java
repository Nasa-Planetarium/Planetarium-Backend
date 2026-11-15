package io.emberline.provenance.domain;

import java.time.LocalDate;

/** One row of satellite_month_status (v1.2, only when a release ships the file), values as the file has them. */
public record SatelliteMonth(String satellite, int year, int month, Integer obsDays, Integer lowDays, Integer missingDays,
                             Integer daysInMonth, LocalDate firstDay, LocalDate lastDay, String monthStatus) {
}

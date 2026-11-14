package io.emberline.provenance.domain;

import java.time.Instant;
import java.time.LocalDate;

/** One row of satellite_day_status (only when a release ships the file). */
public record SatelliteDay(String satellite, LocalDate date, String status, String dataSource, Instant updatedAt) {
}

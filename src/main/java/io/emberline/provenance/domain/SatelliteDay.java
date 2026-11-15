package io.emberline.provenance.domain;

import java.time.Instant;
import java.time.LocalDate;

/** One row of satellite_day_status (only when a release ships the file); note is the v1.2 file's, as-is. */
public record SatelliteDay(String satellite, LocalDate date, String status, String dataSource, Instant updatedAt, String note) {
}

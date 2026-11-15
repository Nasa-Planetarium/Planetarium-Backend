package io.emberline.provenance.domain;

import java.time.Instant;
import java.time.LocalDate;

/**
 * One row of satellite_day_status (only when a release ships the file), values as the file has them: {@code date} is
 * the file's {@code date_utc}; note, n_det_global, coverage and ratio_to_seasonal are the v1.2 file's.
 */
public record SatelliteDay(String satellite, LocalDate date, String status, String dataSource, Instant updatedAt, String note,
                           Long nDetGlobal, String coverage, Double ratioToSeasonal) {
}

package io.emberline.provenance.application.port.in;

import java.time.LocalDate;

/** start/end of /satellite-status; null = default (last 30 days of the release). */
public record SatelliteStatusQuery(LocalDate start, LocalDate end) {
}

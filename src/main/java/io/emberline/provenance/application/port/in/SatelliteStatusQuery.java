package io.emberline.provenance.application.port.in;

import java.time.LocalDate;
import java.util.List;

/**
 * /satellite-status after format validation: start/end (null = default: freq=day the 30 days up to the file's last
 * date_utc, freq=month every month), freq {@code day} or {@code month}, satellites (empty = all).
 */
public record SatelliteStatusQuery(LocalDate start, LocalDate end, String freq, List<String> satellites) {

    public SatelliteStatusQuery {
        satellites = satellites == null ? List.of() : List.copyOf(satellites);
    }
}

package io.emberline.hotspots.domain;

import java.time.Instant;

/** The public order of points: acq_datetime_utc, latitude, longitude, satellite. */
public record SortKey(Instant acqDatetimeUtc, double latitude, double longitude, String satellite) {
}

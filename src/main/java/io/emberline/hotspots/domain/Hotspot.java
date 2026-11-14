package io.emberline.hotspots.domain;

import java.time.Instant;
import java.time.LocalDate;

/** One row of harmonized_hotspots (the 19 file columns, in file order; names follow the file, E1). */
public record Hotspot(double latitude, double longitude, Instant acqDatetimeUtc, LocalDate dateLocal, String satellite,
                      String instrument, Float frp, String confidence, String daynight, long cellId, String adm0A3,
                      String adm1Code, float harmonizedWeight, Float weightLo, Float weightHi, String weightSource,
                      boolean isReference, String source, String methodVersion) {

    public SortKey key() {
        return new SortKey(acqDatetimeUtc, latitude, longitude, satellite);
    }
}

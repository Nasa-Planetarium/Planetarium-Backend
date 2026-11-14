package io.emberline.catalog.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/** Bounding box of an AOI boundary [west, south, east, north] (WGS84), computed with JTS at ingest. */
public record Bbox(double west, double south, double east, double north) {

    /** As served: 3 decimals (prototype {@code round(x, 3)}). */
    public List<Double> rounded() {
        return List.of(r(west), r(south), r(east), r(north));
    }

    private static double r(double v) {
        return BigDecimal.valueOf(v).setScale(3, RoundingMode.HALF_EVEN).doubleValue();
    }
}

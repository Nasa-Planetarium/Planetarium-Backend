package io.emberline.external.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * One FIRMS WMS GetMap request as the server rebuilds it from allow-listed parameters: the FIRMS layer name, the
 * bbox clipped to the supported Korea area (oracle firms.py), size and optional TIME. No client string reaches the
 * upstream URL unvalidated.
 */
public record WmsTile(String firmsLayer, double west, double south, double east, double north, int width, int height,
                      String time) {

    /** Contract layer → FIRMS layer (contract enum; names from the FIRMS WMS info page). */
    public static final Map<String, String> LAYERS = Map.of(
            "viirs_noaa20_24h", "fires_viirs_noaa20_24",
            "viirs_noaa21_24h", "fires_viirs_noaa21_24",
            "modis_24h", "fires_modis_24",
            "viirs_noaa20_7d", "fires_viirs_noaa20_7");

    /** Supported area [w, s, e, n] (plan D6). */
    public static final List<Double> KOREA = List.of(124.5, 33.0, 132.0, 38.7);

    /** The tile for a validated query, or empty when the bbox does not overlap the Korea area. */
    public static Optional<WmsTile> clipped(String layer, double w, double s, double e, double n, int width, int height,
                                            String time) {
        double cw = round(Math.max(w, KOREA.get(0)));
        double cs = round(Math.max(s, KOREA.get(1)));
        double ce = round(Math.min(e, KOREA.get(2)));
        double cn = round(Math.min(n, KOREA.get(3)));
        if (!(cw < ce && cs < cn)) {
            return Optional.empty();
        }
        return Optional.of(new WmsTile(LAYERS.get(layer), cw, cs, ce, cn, width, height, time));
    }

    /** {@code w,s,e,n} as plain decimals. */
    public String bboxParam() {
        return plain(west) + "," + plain(south) + "," + plain(east) + "," + plain(north);
    }

    /** Identity of the tile for caching (no key material). */
    public String identity() {
        return String.join("|", firmsLayer, bboxParam(), Integer.toString(width), Integer.toString(height),
                time == null ? "" : time);
    }

    private static double round(double v) {
        return BigDecimal.valueOf(v).setScale(4, RoundingMode.HALF_EVEN).doubleValue();
    }

    private static String plain(double v) {
        return BigDecimal.valueOf(v).stripTrailingZeros().toPlainString();
    }
}

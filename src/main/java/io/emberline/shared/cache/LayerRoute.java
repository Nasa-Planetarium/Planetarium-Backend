package io.emberline.shared.cache;

import java.util.Arrays;
import java.util.regex.Pattern;

/**
 * The routes whose answers live in the {@link LayerCache}, one key space each: the code is the key segment after
 * {@code c:layer:} and is ':'-free, so two routes never share a key (an enum, so a code is declared once for every
 * module). Add a route here before caching a new layer.
 */
public enum LayerRoute {
    FRP("frp"),
    SATELLITE_STATUS("satellite-status"),
    FIRE_EVENTS("fire-events"),
    FIRE_EVENT("fire-event"),
    FWI("fwi"),
    SPREAD_NOWCAST("spread-nowcast"),
    FIRE_REGIME("fire-regime"),
    OCCURRENCE_GRID("occurrence-grid"),
    OCCURRENCE_AOIS("occurrence-aois"),
    AOI_OCCURRENCE("aoi-occurrence"),
    /** {@code POST /forecast}: the forecast service's answers, keyed by its own version ({@link LayerCache#findText}). */
    FORECAST("forecast");

    static final Pattern CODE = Pattern.compile("^[a-z][a-z0-9-]{0,31}$");

    static {
        if (Arrays.stream(values()).map(LayerRoute::code).distinct().count() != values().length
                || !Arrays.stream(values()).allMatch(r -> CODE.matcher(r.code).matches())) {
            throw new IllegalStateException("layer route codes must be distinct and match " + CODE);
        }
    }

    private final String code;

    LayerRoute(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}

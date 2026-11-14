package io.emberline.provenance.domain;

import java.util.List;
import java.util.Map;

/**
 * Which satellites stand behind a month's value_source (m2v_meta.json model_names / harmonization text):
 * terra = Terra alone (2000-11 ~ 2002-06), modis_m2v = Terra + Aqua, snpp_observed = S-NPP, n20_bridge = NOAA-20,
 * n21_bridge = NOAA-21. A lookup table, not a computation of values.
 */
public final class SatelliteCoverage {

    public static final Map<String, List<String>> SATELLITES = Map.of(
            "modis_m2v_terra", List.of("Terra"),
            "modis_m2v", List.of("Terra", "Aqua"),
            "snpp_observed", List.of("N"),
            "n20_bridge", List.of("N20"),
            "n21_bridge", List.of("N21"));
    public static final Map<String, String> INSTRUMENT = Map.of(
            "N", "VIIRS", "N20", "VIIRS", "N21", "VIIRS", "Terra", "MODIS", "Aqua", "MODIS");
    /** Contract order of satellites. */
    public static final List<String> ORDER = List.of("N", "N20", "N21", "Terra", "Aqua");

    private SatelliteCoverage() {
    }

    public static List<String> of(String valueSource) {
        return valueSource == null ? List.of() : SATELLITES.getOrDefault(valueSource, List.of());
    }
}

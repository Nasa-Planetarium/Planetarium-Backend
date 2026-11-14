package io.emberline.shared.release.load;

import java.util.Map;
import java.util.Set;

/**
 * Code values of the hand-off (contract enums) and the v0 names the loaders translate (hand-off §5). Translated
 * names are reported as aliases_used; responses only ever carry v1 names.
 */
public final class CodeValues {

    public static final Set<String> VALUE_SOURCES = Set.of("modis_m2v_terra", "modis_m2v", "snpp_observed", "n20_bridge", "n21_bridge");
    public static final Map<String, String> VALUE_SOURCE_V0 = Map.of(
            "modis_harmonized", "modis_m2v",
            "noaa20_bridged", "n20_bridge",
            "modis_harmonized_terra_only", "modis_m2v_terra",
            "noaa21_bridged", "n21_bridge");
    public static final Set<String> DATA_SOURCES = Set.of("SP", "NRT");
    public static final Set<String> STATUSES = Set.of("ok", "nrt", "partial", "missing", "low_sample");
    public static final Set<String> VERDICTS = Set.of("above", "similar", "below", "uncertain");

    private CodeValues() {
    }

    public static String valueSource(String fileValue) {
        return fileValue == null ? null : VALUE_SOURCE_V0.getOrDefault(fileValue, fileValue);
    }
}

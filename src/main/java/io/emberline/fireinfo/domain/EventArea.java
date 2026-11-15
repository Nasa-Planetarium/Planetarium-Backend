package io.emberline.fireinfo.domain;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Which fire events an area selects (contract {@code listFireEvents}, MAPPING §8.4):
 * {@code KR} → {@code country == 'KOR'}, {@code ADM0_ARG} → {@code country == 'ARG'}, {@code SIDO_*} →
 * {@code sido_nm == aoi_list.name}, {@code ADM1_ARG-*} → {@code 'ADM1_' + adm1_code}, {@code SGG_*} → the event → SGG
 * index resolved from {@code sgg_all} at load (sgg_cd is not used), a box → centroid inside.
 *
 * @param kind the rule
 * @param value country code, adm1_code, SIDO name or SGG aoi_id (null for a box)
 * @param box the box (null for an AOI)
 */
public record EventArea(Kind kind, String value, Bbox box) {

    /** The selection rules. */
    public enum Kind { COUNTRY, ADM1, SIDO, SGG, BBOX }

    /** AOI id → file country code. The AOI id and the {@code country} value differ (KR ↔ KOR). */
    static final Map<String, String> COUNTRIES = Map.of("KR", "KOR", "ADM0_ARG", "ARG");

    public EventArea {
        Objects.requireNonNull(kind);
    }

    public static EventArea of(Bbox box) {
        return new EventArea(Kind.BBOX, null, Objects.requireNonNull(box));
    }

    /**
     * The rule of a covered AOI ({@link KrArgCoverage#covers(String)}); {@code sidoName} is the aoi_list name of a
     * {@code SIDO_*} AOI. Empty for an AOI outside the layer's range.
     */
    public static Optional<EventArea> of(String aoiId, String sidoName) {
        if (COUNTRIES.containsKey(aoiId)) {
            return Optional.of(new EventArea(Kind.COUNTRY, COUNTRIES.get(aoiId), null));
        }
        if (aoiId.startsWith("ADM1_ARG-")) {
            return Optional.of(new EventArea(Kind.ADM1, aoiId.substring("ADM1_".length()), null));
        }
        if (aoiId.startsWith("SIDO_")) {
            return Optional.of(new EventArea(Kind.SIDO, Objects.requireNonNull(sidoName), null));
        }
        if (aoiId.startsWith("SGG_")) {
            return Optional.of(new EventArea(Kind.SGG, aoiId, null));
        }
        return Optional.empty();
    }
}

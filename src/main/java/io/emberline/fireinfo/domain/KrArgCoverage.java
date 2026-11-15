package io.emberline.fireinfo.domain;

import java.util.List;

/**
 * The range of the Korea / Argentina layers (contract {@code listFireEvents} and {@code getSpreadNowcast}):
 * AOIs {@code KR}, {@code SIDO_*}, {@code SGG_*}, {@code ADM0_ARG}, {@code ADM1_ARG-*}; boxes overlapping the KR box
 * {@code [124.5, 33.0, 132.0, 38.7]} or the ARG box {@code [-73.5, -55.2, -53.4, -21.5]}. Neighbour AOIs (PRK, JPN,
 * PRY …) are out of range even when some of their rows exist — the layer holds only part of them.
 */
public final class KrArgCoverage {

    public static final Bbox KR_BOX = new Bbox(124.5, 33.0, 132.0, 38.7);
    public static final Bbox ARG_BOX = new Bbox(-73.5, -55.2, -53.4, -21.5);
    public static final List<Bbox> BOXES = List.of(KR_BOX, ARG_BOX);

    private KrArgCoverage() {
    }

    public static boolean covers(String aoiId) {
        return "KR".equals(aoiId) || "ADM0_ARG".equals(aoiId) || aoiId.startsWith("SIDO_") || aoiId.startsWith("SGG_")
                || aoiId.startsWith("ADM1_ARG-");
    }

    public static boolean covers(Bbox box) {
        return BOXES.stream().anyMatch(box::overlaps);
    }
}

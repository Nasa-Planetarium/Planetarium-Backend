package io.emberline.fireinfo.domain;

import java.util.Arrays;
import java.util.Optional;

/**
 * Level of a spread cell, set by the server from {@code p_spread} (contract {@code getSpreadNowcast}):
 * {@code low} &lt; 0.05 ≤ {@code moderate} ≤ 0.20 &lt; {@code high} (the model team's quickstart: {@code > 0.20} high,
 * {@code ≥ 0.05} moderate). {@code p_spread} is compared as stored (a file FLOAT 0.2 is stored as 0.2, so it is moderate).
 */
public enum SpreadLevel {
    LOW("low"), MODERATE("moderate"), HIGH("high");

    public static final double MODERATE_FROM = 0.05;
    public static final double HIGH_ABOVE = 0.20;

    private final String code;

    SpreadLevel(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static SpreadLevel of(double pSpread) {
        if (pSpread > HIGH_ABOVE) {
            return HIGH;
        }
        return pSpread >= MODERATE_FROM ? MODERATE : LOW;
    }

    public static Optional<SpreadLevel> ofCode(String code) {
        return Arrays.stream(values()).filter(l -> l.code.equals(code)).findFirst();
    }

    /** Whether this level is {@code min} or above ({@code min_level} filter). */
    public boolean atLeast(SpreadLevel min) {
        return ordinal() >= min.ordinal();
    }
}

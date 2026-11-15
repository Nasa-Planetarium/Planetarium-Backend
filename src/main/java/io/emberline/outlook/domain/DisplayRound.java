package io.emberline.outlook.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Rounding for display only (contract getGridOccurrence: {@code fields=full} 5 decimals, {@code fields=map} 4): the
 * exact stored double, half-up. Rules (vs_normal, rank, bands, filters) always use the unrounded value.
 */
public final class DisplayRound {

    public static final int FULL = 5;
    public static final int MAP = 4;

    private DisplayRound() {
    }

    public static double round(double value, int decimals) {
        if (!Double.isFinite(value)) {
            return value;
        }
        return new BigDecimal(value).setScale(decimals, RoundingMode.HALF_UP).doubleValue();
    }

    public static Double round(Double value, int decimals) {
        return value == null ? null : round(value.doubleValue(), decimals);
    }
}

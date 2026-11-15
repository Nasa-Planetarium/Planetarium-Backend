package io.emberline.outlook.domain;

/**
 * The model team's display rule (contract schema {@code VsNormal}): {@code p_any − baseline_p} ≥ +0.10 → higher,
 * ≤ −0.10 → lower, in between similar. Computed on the stored doubles (the file's float32 values widened exactly),
 * never on rounded display values; the probabilities themselves are file values.
 */
public enum VsNormal {
    HIGHER("higher"), SIMILAR("similar"), LOWER("lower");

    /** The ±0.10 boundary; a difference exactly equal to it (as a double) is higher / lower. */
    public static final double THRESHOLD = 0.10;

    private final String code;

    VsNormal(String code) {
        this.code = code;
    }

    public static VsNormal of(double pAny, double baselineP) {
        double difference = pAny - baselineP;
        if (difference >= THRESHOLD) {
            return HIGHER;
        }
        return difference <= -THRESHOLD ? LOWER : SIMILAR;
    }

    /** The contract value ({@code higher}, {@code similar}, {@code lower}). */
    public String code() {
        return code;
    }
}

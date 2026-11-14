package io.emberline.harmonized.domain;

/** Month status values: the four file values plus the response-only out_of_range. */
public final class Status {

    public static final String OK = "ok";
    public static final String NRT = "nrt";
    public static final String PARTIAL = "partial";
    public static final String MISSING = "missing";
    public static final String OUT_OF_RANGE = "out_of_range";
    public static final String UNCERTAIN = "uncertain";

    private Status() {
    }

    /** Statuses whose value is a known number (0 when nothing was detected). */
    public static boolean hasValue(String status) {
        return OK.equals(status) || NRT.equals(status) || PARTIAL.equals(status);
    }
}

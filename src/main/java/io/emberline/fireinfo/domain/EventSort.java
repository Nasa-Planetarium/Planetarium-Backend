package io.emberline.fireinfo.domain;

import java.util.Arrays;
import java.util.Optional;

/**
 * Sort of the fire event list (contract enum {@code sort}): every key descending (largest / latest first; a null
 * {@code burned_area_km2} after every value), ties by {@code event_id} ascending. Default {@code sum_weight}.
 */
public enum EventSort {
    SUM_WEIGHT("sum_weight"), START_DATE("start_date"), BURNED_AREA_KM2("burned_area_km2");

    private final String code;

    EventSort(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static Optional<EventSort> ofCode(String code) {
        return Arrays.stream(values()).filter(s -> s.code.equals(code)).findFirst();
    }
}

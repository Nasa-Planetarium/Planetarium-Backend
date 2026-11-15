package io.emberline.fireinfo.domain;

import java.time.LocalDate;
import java.util.Objects;

/**
 * The last event of a page in sort order (keyset): its sort value — {@code value} for sum_weight and burned_area_km2
 * (null = a null burned_area_km2), {@code date} for start_date — and its event_id.
 */
public record EventPosition(EventSort sort, Double value, LocalDate date, String eventId) {

    public EventPosition {
        Objects.requireNonNull(sort);
        Objects.requireNonNull(eventId);
    }

    public static EventPosition after(EventSort sort, FireEvent last) {
        Double value = sort == EventSort.SUM_WEIGHT ? Double.valueOf(last.sumWeight())
                : sort == EventSort.BURNED_AREA_KM2 ? last.burnedAreaKm2() : null;
        return new EventPosition(sort, value, sort == EventSort.START_DATE ? last.startDate() : null, last.eventId());
    }
}

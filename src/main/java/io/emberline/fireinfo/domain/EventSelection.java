package io.emberline.fireinfo.domain;

import java.time.LocalDate;
import java.util.Objects;

/** The events of an area whose period overlaps start..end ({@code start_date ≤ end} and {@code end_date ≥ start}). */
public record EventSelection(EventArea area, LocalDate start, LocalDate end, int minDet, EventSort sort) {

    public EventSelection {
        Objects.requireNonNull(area);
        Objects.requireNonNull(start);
        Objects.requireNonNull(end);
        Objects.requireNonNull(sort);
    }
}

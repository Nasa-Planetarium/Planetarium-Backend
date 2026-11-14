package io.emberline.harmonized.application.port.in;

import java.time.LocalDate;

/** Date range (date_local, inclusive) and frequency ({@code month} or {@code day}) of an AOI summary. */
public record SummaryQuery(LocalDate start, LocalDate end, String freq) {
}

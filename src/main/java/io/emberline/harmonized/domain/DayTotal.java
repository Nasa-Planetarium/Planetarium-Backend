package io.emberline.harmonized.domain;

import java.time.LocalDate;

/**
 * Σ harmonized_cell_day over a cell set for one local date. (cell_id, date_local) is not unique in the release (day-1
 * residual carrier rows, R-CD1), so every column is a SUM with NULLs skipped; {@code valueSource} is the source of the
 * observed rows (n_points &gt; 0), null when the day has none.
 */
public record DayTotal(LocalDate date, double value, Double valueLo, Double valueHi, long nPoints, String valueSource) {
}

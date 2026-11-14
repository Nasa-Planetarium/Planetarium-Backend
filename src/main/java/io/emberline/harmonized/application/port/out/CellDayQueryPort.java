package io.emberline.harmonized.application.port.out;

import io.emberline.harmonized.domain.DayTotal;
import io.emberline.harmonized.domain.ResidualSums;
import io.emberline.shared.release.ReleaseRef;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

/**
 * harmonized_cell_day.parquet of the pinned release, read in place (not loaded into PostgreSQL, ruling R-CD1).
 */
public interface CellDayQueryPort {

    /** Whether the release ships harmonized_cell_day.parquet. */
    boolean available(ReleaseRef release);

    /** Σ per local date over the cells, for dates in [from, to] that have rows (days without rows are absent). */
    List<DayTotal> dayTotals(ReleaseRef release, long[] cells, LocalDate from, LocalDate to);

    /** Σ residual_month (day-1 rows) per month over the cells, for months from..to; months without residual are absent. */
    Map<YearMonth, ResidualSums> monthResiduals(ReleaseRef release, long[] cells, YearMonth from, YearMonth to);
}

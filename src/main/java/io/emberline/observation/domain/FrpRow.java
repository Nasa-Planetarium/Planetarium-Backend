package io.emberline.observation.domain;

import java.time.LocalDate;

/**
 * One row of harmonized_cell_day_frp as stored (freq day: {@code date} = date_local), or one cell-month group
 * (freq month: {@code date} = the month's first day, Σ frp_sum, Σ frp_snpp_equiv_sum, max frp_snpp_equiv_max,
 * Σ n_points — grouping, not a recomputation of values).
 */
public record FrpRow(long cellId, LocalDate date, Double frpSum, Double frpSnppEquivSum, Float frpSnppEquivMax,
                     long nPoints) {
}

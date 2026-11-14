package io.emberline.harmonized.application.port.out;

import io.emberline.shared.release.ReleaseRef;
import java.time.YearMonth;
import java.util.Map;

/** harmonized_residual_cell_month.parquet read in place: Σ value per month and residual_type over a cell set. */
public interface ResidualQueryPort {

    boolean available(ReleaseRef release);

    Map<YearMonth, Map<String, Double>> residualsByType(ReleaseRef release, long[] cells, YearMonth from, YearMonth to);
}

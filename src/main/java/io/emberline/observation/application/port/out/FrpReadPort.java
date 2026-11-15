package io.emberline.observation.application.port.out;

import io.emberline.observation.domain.CellIdRange;
import io.emberline.observation.domain.FrpRow;
import java.time.LocalDate;
import java.util.List;

/** observation.cell_day_frp of one release. */
public interface FrpReadPort {

    /** Whether the release loaded harmonized_cell_day_frp.parquet (its partition holds rows). */
    boolean loaded(long releaseId);

    /**
     * Rows with date_local in [start, end] and the cell in {@code range} (and in {@code cells} when not null), by
     * date_local then cell_id, at most {@code limit}.
     */
    List<FrpRow> days(long releaseId, LocalDate start, LocalDate end, CellIdRange range, long[] cells, int limit);

    /** The same rows grouped per cell and month (Σ, max, Σ n_points), by month then cell_id, at most {@code limit}. */
    List<FrpRow> months(long releaseId, LocalDate start, LocalDate end, CellIdRange range, long[] cells, int limit);
}

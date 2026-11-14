package io.emberline.observation.application.port.out;

import io.emberline.observation.domain.CellBox;
import io.emberline.observation.domain.CellRow;
import java.util.List;
import java.util.Optional;

/** observation.cell_month of one release. */
public interface CellReadPort {

    /** Rows of the month whose cell center lies in the box, by cell_id, at most {@code limit} rows. */
    List<CellRow> cellsInBox(long releaseId, int year, int month, CellBox box, int limit);

    /** The month's status in the cell table (uniform per month in the release); empty when the month has no row. */
    Optional<String> monthStatus(long releaseId, int year, int month);
}

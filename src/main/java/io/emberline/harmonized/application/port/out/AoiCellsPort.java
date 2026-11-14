package io.emberline.harmonized.application.port.out;

import io.emberline.shared.release.ReleaseRef;
import java.util.Optional;

/**
 * The 0.1° cells of an AOI: cells whose centre (cell_lat, cell_lon of harmonized_cell_month) lies inside the AOI
 * boundary (bbox prefilter, then JTS PreparedGeometry contains). Empty when the release has no boundary for the AOI.
 */
public interface AoiCellsPort {

    Optional<long[]> cellsInside(ReleaseRef release, String aoiId);
}

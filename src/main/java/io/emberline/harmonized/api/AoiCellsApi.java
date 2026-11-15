package io.emberline.harmonized.api;

import io.emberline.shared.release.ReleaseRef;
import java.util.Optional;

/**
 * The 0.1° cells of an AOI by ruling R-SP1 (the rule /summary uses): cells of harmonized_cell_month whose centre lies
 * inside the AOI boundary of the release's aoi_boundaries.geojson. Cached per (release, aoi_id).
 */
public interface AoiCellsApi {

    /** Sorted cell ids; empty when the release has no boundary for the AOI. The caller checks the AOI exists. */
    Optional<long[]> cellsInside(ReleaseRef release, String aoiId);
}

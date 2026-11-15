package io.emberline.harmonized.api;

import io.emberline.shared.release.ReleaseRef;
import java.util.Optional;

/**
 * Ruling R-SP1 for other contexts: a cell belongs to an AOI when its centre lies inside the AOI boundary of the pinned
 * release (the AOI's feature in aoi_boundaries.geojson, JTS PreparedGeometry contains — a centre on the boundary line
 * is outside). The same test harmonized applies to its own 0.1° cells; the caller brings its own cell centres.
 */
public interface AoiCellRule {

    /**
     * For every centre {@code (lats[i], lons[i])} in degrees, whether it lies inside the AOI boundary; empty when the
     * release has no boundary for the AOI. The arrays have the same length.
     */
    Optional<boolean[]> centresInside(ReleaseRef release, String aoiId, double[] lats, double[] lons);
}

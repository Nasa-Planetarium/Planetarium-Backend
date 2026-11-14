package io.emberline.catalog.api;

import io.emberline.shared.release.ReleaseRef;
import java.util.List;
import java.util.Set;

/** Boundaries and level lists of the pinned release for other contexts (observation cell selection, cache warm-up). */
public interface AoiGeometryApi {

    /** The AOI's boundary or 404 unknown_aoi (param {@code param}); 400 invalid_param when the id is malformed. */
    AoiGeometry requireGeometry(ReleaseRef release, String aoiId, String param);

    /** AOIs whose hand-off level is one of {@code levels}, in list order. */
    List<AoiSummary> aoisAtLevels(ReleaseRef release, Set<String> levels);
}

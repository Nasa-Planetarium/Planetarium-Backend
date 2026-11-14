package io.emberline.catalog.api;

import io.emberline.shared.release.ReleaseRef;
import java.util.Optional;

/** Public surface of catalog for other contexts (in-memory per release, no SQL per call). */
public interface CatalogApi {

    /** The AOI or 404 unknown_aoi (param {@code param}); 400 invalid_param when the id is malformed. */
    AoiSummary requireAoi(ReleaseRef release, String aoiId, String param);

    Optional<AoiSummary> findAoi(ReleaseRef release, String aoiId);
}

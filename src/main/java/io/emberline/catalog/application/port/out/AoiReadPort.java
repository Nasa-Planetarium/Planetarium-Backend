package io.emberline.catalog.application.port.out;

import io.emberline.catalog.domain.Aoi;
import java.util.List;
import java.util.Optional;

/** catalog.aoi / catalog.aoi_boundary of one release. */
public interface AoiReadPort {

    List<Aoi> all(long releaseId);

    /** The boundary geometry JSON as stored; empty when the AOI has no boundary. */
    Optional<String> boundaryGeometry(long releaseId, String aoiId);
}

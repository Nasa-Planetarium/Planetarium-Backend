package io.emberline.outlook.application.port.in;

import io.emberline.shared.release.ReleaseRef;

/** GET /api/v1/aois/{aoiId}/occurrence (contract getAoiOccurrence). */
public interface GetAoiOccurrenceUseCase {

    LayerBody aoi(ReleaseRef release, String aoiId);
}

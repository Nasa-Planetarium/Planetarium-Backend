package io.emberline.outlook.application.port.in;

import io.emberline.shared.release.ReleaseRef;

/** GET /api/v1/occurrence/aois?parent_id (contract listAoiOccurrence). */
public interface ListAoiOccurrenceUseCase {

    LayerBody list(ReleaseRef release, String parentId);
}

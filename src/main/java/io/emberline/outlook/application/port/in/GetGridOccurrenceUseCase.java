package io.emberline.outlook.application.port.in;

import io.emberline.shared.release.ReleaseRef;

/** GET /api/v1/occurrence/grid (contract getGridOccurrence). */
public interface GetGridOccurrenceUseCase {

    LayerBody grid(ReleaseRef release, GridOccurrenceQuery query);
}

package io.emberline.hotspots.application.port.in;

import io.emberline.shared.release.ReleaseRef;

/** GET /api/v1/hotspots. */
public interface ListHotspotsUseCase {

    HotspotListResponse list(ReleaseRef release, HotspotListQuery query);
}

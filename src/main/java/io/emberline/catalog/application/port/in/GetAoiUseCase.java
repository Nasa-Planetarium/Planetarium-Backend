package io.emberline.catalog.application.port.in;

import io.emberline.shared.release.ReleaseRef;

public interface GetAoiUseCase {

    AoiDetailResponse get(ReleaseRef release, String aoiId);

    BoundaryFeature boundary(ReleaseRef release, String aoiId);
}

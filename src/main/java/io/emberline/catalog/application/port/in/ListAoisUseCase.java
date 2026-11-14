package io.emberline.catalog.application.port.in;

import io.emberline.shared.release.ReleaseRef;

public interface ListAoisUseCase {

    AoiListResponse list(ReleaseRef release, AoiListQuery query);
}

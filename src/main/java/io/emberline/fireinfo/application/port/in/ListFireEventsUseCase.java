package io.emberline.fireinfo.application.port.in;

import io.emberline.shared.release.ReleaseRef;

public interface ListFireEventsUseCase {

    FireEventListResponse list(ReleaseRef release, FireEventListQuery query);
}

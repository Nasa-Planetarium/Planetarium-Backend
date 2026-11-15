package io.emberline.fireinfo.application.port.in;

import io.emberline.shared.release.ReleaseRef;

public interface GetFireEventUseCase {

    /** 400 invalid_param 40002 when the id breaks the contract FireEventId pattern; 404 not_found 40402 when absent. */
    FireEventDetailResponse event(ReleaseRef release, String eventId);
}

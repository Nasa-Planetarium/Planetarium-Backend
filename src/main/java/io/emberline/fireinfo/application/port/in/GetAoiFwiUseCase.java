package io.emberline.fireinfo.application.port.in;

import io.emberline.shared.release.ReleaseRef;

public interface GetAoiFwiUseCase {

    FwiResponse fwi(ReleaseRef release, String aoiId, FwiQuery query);
}

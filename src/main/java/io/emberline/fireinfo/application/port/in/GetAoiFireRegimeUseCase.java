package io.emberline.fireinfo.application.port.in;

import io.emberline.shared.release.ReleaseRef;

public interface GetAoiFireRegimeUseCase {

    FireRegimeResponse regime(ReleaseRef release, String aoiId, FireRegimeQuery query);
}

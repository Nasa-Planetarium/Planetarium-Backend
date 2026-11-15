package io.emberline.fireinfo.application.port.in;

import io.emberline.shared.release.ReleaseRef;

public interface GetSpreadNowcastUseCase {

    SpreadNowcastResponse nowcast(ReleaseRef release, SpreadNowcastQuery query);
}

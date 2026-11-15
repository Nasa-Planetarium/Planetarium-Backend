package io.emberline.observation.application.port.in;

import io.emberline.shared.release.ReleaseRef;

public interface ListCellFrpUseCase {

    CellFrpList frp(ReleaseRef release, CellFrpQuery query);
}

package io.emberline.observation.application.port.in;

import io.emberline.shared.release.ReleaseRef;

public interface ListCellsUseCase {

    CellCollection cells(ReleaseRef release, CellsQuery query);
}

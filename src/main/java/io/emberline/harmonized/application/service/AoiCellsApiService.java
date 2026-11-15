package io.emberline.harmonized.application.service;

import io.emberline.harmonized.api.AoiCellsApi;
import io.emberline.harmonized.application.port.out.AoiCellsPort;
import io.emberline.shared.release.ReleaseRef;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** {@link AoiCellsApi} for other modules: the same cell set (and cache) as /summary. */
@Service
class AoiCellsApiService implements AoiCellsApi {

    private final AoiCellsPort cells;

    AoiCellsApiService(AoiCellsPort cells) {
        this.cells = cells;
    }

    @Override
    public Optional<long[]> cellsInside(ReleaseRef release, String aoiId) {
        return cells.cellsInside(release, aoiId);
    }
}

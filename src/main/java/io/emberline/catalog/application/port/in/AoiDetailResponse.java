package io.emberline.catalog.application.port.in;

import java.util.List;

/** GET /api/v1/aois/{aoiId} (contract: Aoi + method_version). */
public record AoiDetailResponse(String aoiId, String nameKo, String nameEn, String level, String parentId, int nCells,
                                boolean hidden, boolean hasData, String firstMonth, String lastMonth, List<Double> bbox,
                                String methodVersion) {
}

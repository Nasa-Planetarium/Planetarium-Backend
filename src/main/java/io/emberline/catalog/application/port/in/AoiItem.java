package io.emberline.catalog.application.port.in;

import java.util.List;

/** One AOI in a list (contract schema Aoi without method_version). */
public record AoiItem(String aoiId, String nameKo, String nameEn, String level, String parentId, int nCells,
                      boolean hidden, boolean hasData, String firstMonth, String lastMonth, List<Double> bbox) {
}

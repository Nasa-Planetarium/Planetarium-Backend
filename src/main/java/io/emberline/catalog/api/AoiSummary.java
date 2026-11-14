package io.emberline.catalog.api;

/** What other contexts may know about an AOI (level is the hand-off value). */
public record AoiSummary(String aoiId, String nameKo, String nameEn, String level, String parentId, int nCells,
                         boolean hidden) {
}

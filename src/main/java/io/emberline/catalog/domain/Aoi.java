package io.emberline.catalog.domain;

import java.util.Locale;

/** One row of aoi_list (+ derived has_data / first / last month / bbox) of one release. */
public record Aoi(String aoiId, String nameKo, String nameEn, AoiLevel level, String levelFile, String parentId,
                  int nCells, boolean hidden, boolean hasData, String firstMonth, String lastMonth, Bbox bbox) {

    /** Case-insensitive literal substring match on name_ko, name_en or aoi_id (no LIKE, so % and _ are plain). */
    public boolean matches(String q) {
        String needle = q.toLowerCase(Locale.ROOT);
        return contains(nameKo, needle) || contains(nameEn, needle) || contains(aoiId, needle);
    }

    private static boolean contains(String haystack, String needle) {
        return haystack != null && haystack.toLowerCase(Locale.ROOT).contains(needle);
    }
}

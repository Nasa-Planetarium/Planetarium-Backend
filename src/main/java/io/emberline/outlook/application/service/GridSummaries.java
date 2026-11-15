package io.emberline.outlook.application.service;

import io.emberline.harmonized.api.AoiCellRule;
import io.emberline.outlook.domain.GridCell;
import io.emberline.outlook.domain.GridLayer;
import io.emberline.outlook.domain.GridSummaryStats;
import io.emberline.shared.release.ReleaseRef;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Grid summaries of AOIs that are not file rows: the 0.5° cells whose centre lies inside the AOI boundary — ruling
 * R-SP1 through harmonized's {@link AoiCellRule}, not a copy of it. Empty when the AOI has no such cell (no boundary
 * in the release counts as none). Kept for the last {@value #KEEP} (release, aoi_id) pairs.
 */
@Component
class GridSummaries {

    static final int KEEP = 256;

    private record Key(long releaseId, String aoiId) {
    }

    private final AoiCellRule cellRule;
    private final Map<Key, Optional<GridSummaryStats>> cache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Key, Optional<GridSummaryStats>> eldest) {
            return size() > KEEP;
        }
    };

    GridSummaries(AoiCellRule cellRule) {
        this.cellRule = cellRule;
    }

    Optional<GridSummaryStats> of(ReleaseRef release, String aoiId, GridLayer grid) {
        Key key = new Key(release.releaseId(), aoiId);
        synchronized (cache) {
            Optional<GridSummaryStats> hit = cache.get(key);
            if (hit != null) {
                return hit;
            }
        }
        Optional<GridSummaryStats> stats = cellRule.centresInside(release, aoiId, grid.lats(), grid.lons())
                .map(inside -> cellsInside(grid.cells(), inside))
                .filter(cells -> !cells.isEmpty())
                .map(GridSummaryStats::of);
        synchronized (cache) {
            cache.put(key, stats);
        }
        return stats;
    }

    private static List<GridCell> cellsInside(List<GridCell> cells, boolean[] inside) {
        List<GridCell> out = new ArrayList<>();
        for (int i = 0; i < inside.length; i++) {
            if (inside[i]) {
                out.add(cells.get(i));
            }
        }
        return out;
    }
}

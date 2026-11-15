package io.emberline.outlook.application.service;

import io.emberline.outlook.application.port.out.OccurrenceReadPort;
import io.emberline.outlook.domain.AoiLayer;
import io.emberline.outlook.domain.GridLayer;
import io.emberline.shared.error.DataUnavailableException;
import io.emberline.shared.error.ErrorCode;
import io.emberline.shared.release.ReleaseRef;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * The two occurrence files of a release in memory (52,855 + 252 rows), read once per release and kept for the last
 * {@value #KEEP} releases (a switch and its rollback). A release without the file answers 503 layer_not_loaded
 * (MAPPING §8.3 「파일 자체 없음」).
 */
@Component
class OccurrenceLayers {

    static final int KEEP = 2;
    static final String GRID_FILE = "grid_occurrence_nextmonth_latest";
    static final String AOI_FILE = "aoi_occurrence_nextmonth_latest";

    private final OccurrenceReadPort read;
    private final Map<Long, Optional<GridLayer>> grids = lru();
    private final Map<Long, Optional<AoiLayer>> aois = lru();

    OccurrenceLayers(OccurrenceReadPort read) {
        this.read = read;
    }

    GridLayer grid(ReleaseRef release) {
        return cached(grids, release.releaseId(), () -> read.grid(release.releaseId()))
                .orElseThrow(() -> notLoaded(GRID_FILE));
    }

    AoiLayer aois(ReleaseRef release) {
        return cached(aois, release.releaseId(), () -> read.aois(release.releaseId()))
                .orElseThrow(() -> notLoaded(AOI_FILE));
    }

    private static <T> Optional<T> cached(Map<Long, Optional<T>> cache, long releaseId, Supplier<Optional<T>> load) {
        synchronized (cache) {
            Optional<T> hit = cache.get(releaseId);
            if (hit != null) {
                return hit;
            }
        }
        Optional<T> loaded = load.get();
        synchronized (cache) {
            cache.put(releaseId, loaded);
        }
        return loaded;
    }

    private static DataUnavailableException notLoaded(String file) {
        return new DataUnavailableException(ErrorCode.LAYER_NOT_LOADED, null, file + " is not loaded for the active release");
    }

    private static <T> Map<Long, T> lru() {
        return new LinkedHashMap<>(4, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Long, T> eldest) {
                return size() > KEEP;
            }
        };
    }
}

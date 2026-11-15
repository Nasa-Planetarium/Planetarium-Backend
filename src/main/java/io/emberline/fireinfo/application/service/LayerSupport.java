package io.emberline.fireinfo.application.service;

import io.emberline.fireinfo.application.port.out.FireInfoReadPort;
import io.emberline.fireinfo.domain.LayerFile;
import io.emberline.shared.cache.LayerCache;
import io.emberline.shared.cache.LayerRoute;
import io.emberline.shared.error.DataUnavailableException;
import io.emberline.shared.error.ErrorCode;
import io.emberline.shared.release.ReleaseRef;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * What every fire-info route shares: the layer cache wrap (shared {@link LayerCache}, key = route + data_version +
 * normalized query, filled after validation, so errors are never cached) and the "layer file loaded?" check (503
 * data_not_loaded 50502).
 */
@Component
class LayerSupport {

    private static final int KEEP = 3;

    private final FireInfoReadPort port;
    private final LayerCache cache;
    private final Map<Long, Set<LayerFile>> loaded = new ConcurrentHashMap<>();

    LayerSupport(FireInfoReadPort port, LayerCache cache) {
        this.port = port;
        this.cache = cache;
    }

    /** The cached response of this release and query, or {@code compute} (then stored). */
    <T> T cached(ReleaseRef release, LayerRoute route, String normalizedQuery, Class<T> type, Supplier<T> compute) {
        return cache.get(route, release, normalizedQuery, type, compute);
    }

    /** 503 data_not_loaded (50502, Retry-After 60) when the release did not ship the layer file. */
    void require(ReleaseRef release, LayerFile file) {
        Set<LayerFile> files = loaded.computeIfAbsent(release.releaseId(), port::layers);
        if (loaded.size() > KEEP) {
            loaded.keySet().stream().sorted().limit(loaded.size() - KEEP).toList().forEach(loaded::remove);
        }
        if (!files.contains(file)) {
            throw new DataUnavailableException(ErrorCode.LAYER_NOT_LOADED, null,
                    file.fileName() + " is not loaded in the active release (준비 중)");
        }
    }
}

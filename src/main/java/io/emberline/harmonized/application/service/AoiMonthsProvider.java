package io.emberline.harmonized.application.service;

import io.emberline.harmonized.application.port.out.AoiMonthCachePort;
import io.emberline.harmonized.application.port.out.AoiMonthReadPort;
import io.emberline.harmonized.domain.AoiMonths;
import io.emberline.shared.release.ReleaseRef;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * The per-AOI bundle: redis-cache first, then one SQL statement, then back into the cache. Callers have already
 * validated the request and resolved the AOI through CatalogApi (unknown ids never reach the cache).
 */
@Component
class AoiMonthsProvider {

    private final AoiMonthCachePort cache;
    private final AoiMonthReadPort read;

    AoiMonthsProvider(AoiMonthCachePort cache, AoiMonthReadPort read) {
        this.cache = cache;
        this.read = read;
    }

    AoiMonths get(ReleaseRef release, String aoiId, String route) {
        Optional<AoiMonths> cached = cache.get(release, aoiId, route);
        if (cached.isPresent()) {
            return cached.get();
        }
        AoiMonths fresh = read.load(release, aoiId, route);
        cache.put(release, fresh, route);
        return fresh;
    }
}

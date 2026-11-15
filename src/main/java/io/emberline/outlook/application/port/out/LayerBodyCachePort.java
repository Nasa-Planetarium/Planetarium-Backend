package io.emberline.outlook.application.port.out;

import io.emberline.shared.cache.LayerRoute;
import io.emberline.shared.json.RawJson;
import io.emberline.shared.release.ReleaseRef;
import java.util.function.Supplier;

/**
 * Serialized layer bodies. The key is the release's data_version plus the normalized query; no TTL (a release switch
 * changes the key). Only successful bodies get here, errors are never cached. A redis-cache failure is a miss.
 */
public interface LayerBodyCachePort {

    /**
     * The JSON of {@code response} for ({@code release}, {@code route}, {@code query}): from this pod's memory when
     * {@code keep} (the whole-world bodies), else from the layer cache, else serialized now and stored.
     */
    RawJson json(ReleaseRef release, LayerRoute route, String query, boolean keep, Supplier<Object> response);
}

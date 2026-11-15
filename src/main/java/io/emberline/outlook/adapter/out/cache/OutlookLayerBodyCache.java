package io.emberline.outlook.adapter.out.cache;

import io.emberline.outlook.application.port.out.LayerBodyCachePort;
import io.emberline.shared.cache.LayerCache;
import io.emberline.shared.cache.LayerRoute;
import io.emberline.shared.json.RawJson;
import io.emberline.shared.release.ReleaseRef;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Serialized outlook bodies on the shared {@link LayerCache} (its redis-cache connection, key builder and back-off;
 * key = route + data_version + normalized query, no TTL). The whole-grid bodies and the KR list ({@code keep}) also stay
 * in this pod's memory for the last {@value #KEEP} keys, keyed by the same layer-cache key. Bodies are written with the
 * application's JsonMapper, so they are byte-identical to what MVC would write.
 */
@Component
class OutlookLayerBodyCache implements LayerBodyCachePort {

    static final int KEEP = 8;

    private final LayerCache cache;
    private final JsonMapper jsonMapper;
    private final Map<String, RawJson> memory = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, RawJson> eldest) {
            return size() > KEEP;
        }
    };

    OutlookLayerBodyCache(LayerCache cache, JsonMapper jsonMapper) {
        this.cache = cache;
        this.jsonMapper = jsonMapper;
    }

    @Override
    public RawJson json(ReleaseRef release, LayerRoute route, String query, boolean keep, Supplier<Object> response) {
        String key = cache.key(route, release, query);
        if (keep) {
            synchronized (memory) {
                RawJson hit = memory.get(key);
                if (hit != null) {
                    return hit;
                }
            }
        }
        RawJson body = new RawJson(cache.json(route, release, query, () -> jsonMapper.writeValueAsString(response.get())));
        if (keep) {
            synchronized (memory) {
                memory.put(key, body);
            }
        }
        return body;
    }
}

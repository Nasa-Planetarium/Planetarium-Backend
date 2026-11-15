package io.emberline.fireinfo.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.emberline.fireinfo.application.port.out.FireInfoReadPort;
import io.emberline.fireinfo.domain.LayerFile;
import io.emberline.shared.cache.LayerCache;
import io.emberline.shared.cache.LayerRoute;
import io.emberline.shared.error.DataUnavailableException;
import io.emberline.shared.error.ErrorCode;
import io.emberline.shared.release.ReleaseRef;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.EnumSet;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * F8 / MAPPING §8.3 (unit): a release without the layer file → 503 data_not_loaded 50502; the cache
 * wrap goes to the shared layer cache with the route, release and normalized query. data_release is
 * {@code ReleaseRef.dataRelease()} (the one helper, pinned here for the fire-info answers).
 */
class LayerSupportTest {

    static final ReleaseRef RELEASE = new ReleaseRef(7L, "v1.2-2026-11-15", "v1-m2v-2026-11-14", "v1.2-2026-11-15#0123456789ab", Instant.EPOCH);

    static FireInfoReadPort port(EnumSet<LayerFile> files) {
        return (FireInfoReadPort) Proxy.newProxyInstance(FireInfoReadPort.class.getClassLoader(), new Class<?>[] {FireInfoReadPort.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("layers")) {
                        return files;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    static LayerCache cache(Object hit, StringBuilder keys) {
        return new LayerCache() {
            @Override
            public <T> T get(LayerRoute route, ReleaseRef release, String normalizedQuery, Class<T> type, Supplier<T> compute) {
                keys.append(key(route, release, normalizedQuery));
                return hit == null ? compute.get() : type.cast(hit);
            }

            @Override
            public String json(LayerRoute route, ReleaseRef release, String normalizedQuery, Supplier<String> serialize) {
                throw new UnsupportedOperationException();
            }

            @Override
            public String key(LayerRoute route, ReleaseRef release, String normalizedQuery) {
                return route.code() + "|" + release.dataVersion() + "|" + normalizedQuery;
            }
        };
    }

    @Test
    void missingLayerFileIs50502() {
        LayerSupport support = new LayerSupport(port(EnumSet.of(LayerFile.FWI_AOI_DAY)), cache(null, new StringBuilder()));
        support.require(RELEASE, LayerFile.FWI_AOI_DAY);
        assertThatThrownBy(() -> support.require(RELEASE, LayerFile.SPREAD_NOWCAST))
                .isInstanceOfSatisfying(DataUnavailableException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(ErrorCode.LAYER_NOT_LOADED);
                    assertThat(e.errorCode().number()).isEqualTo(50502);
                });
    }

    @Test
    void cacheWrapPassesRouteReleaseAndQuery() {
        StringBuilder keys = new StringBuilder();
        LayerSupport support = new LayerSupport(port(EnumSet.noneOf(LayerFile.class)), cache("cached", keys));
        assertThat(support.cached(RELEASE, LayerRoute.FWI, "q", String.class, () -> {
            throw new AssertionError("computed despite a cache hit");
        })).isEqualTo("cached");
        assertThat(keys).hasToString("fwi|v1.2-2026-11-15#0123456789ab|q");
        LayerSupport miss = new LayerSupport(port(EnumSet.noneOf(LayerFile.class)), cache(null, new StringBuilder()));
        assertThat(miss.cached(RELEASE, LayerRoute.FWI, "q", String.class, () -> "fresh")).isEqualTo("fresh");
    }

    @Test
    void dataReleaseIsTheReleaseTag() {
        assertThat(RELEASE.dataRelease()).isEqualTo("data-v1.2-2026-11-15");
        assertThat(new ReleaseRef(1L, "data-v1.2-2026-11-15", "m", "d#0", Instant.EPOCH).dataRelease())
                .isEqualTo("data-v1.2-2026-11-15");
    }
}

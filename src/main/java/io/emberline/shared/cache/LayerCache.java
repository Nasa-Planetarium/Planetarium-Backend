package io.emberline.shared.cache;

import io.emberline.shared.release.ReleaseRef;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Server-side cache of the v1.2 layer answers (contract CacheControlLayer: "Redis 캐시 키 = data_version + 정규화 질의, TTL
 * 없음"), the only one: one redis-cache connection, one key builder, one back-off. The key carries the route, the
 * release's data_version and the normalized query, so a release switch or rollback reads other keys and nothing is ever
 * deleted. Only successful answers are stored: an exception from {@code compute} propagates and leaves no entry. A
 * cache failure is a miss (the answer is computed), never an error.
 */
public interface LayerCache {

    /**
     * An answer record, stored as JSON.
     *
     * @param route           the layer's key space
     * @param normalizedQuery the validated query in one canonical form (parameter order and number format fixed)
     * @param type            the answer's record type
     */
    <T> T get(LayerRoute route, ReleaseRef release, String normalizedQuery, Class<T> type, Supplier<T> compute);

    /** A body the caller serialized itself ({@code serialize} = its JSON text), stored and returned verbatim. */
    String json(LayerRoute route, ReleaseRef release, String normalizedQuery, Supplier<String> serialize);

    /**
     * A stored text answer of a service outside the release (forecast: the service's own data_version and as_of are
     * the {@code version}). The caller decides what is storable, so lookup and store are separate. A cache failure is
     * an empty result.
     *
     * @param route           the answer's key space
     * @param version         what replaces the release's data_version in the key
     * @param normalizedQuery the validated query in one canonical form (callers may hash a long one)
     */
    default Optional<String> findText(LayerRoute route, String version, String normalizedQuery) {
        return Optional.empty();
    }

    /** Stores {@code text} under the {@link #findText} key; {@code ttl} null = no expiry. A cache failure is ignored. */
    default void putText(LayerRoute route, String version, String normalizedQuery, String text, Duration ttl) {
    }

    /** The key of an answer: {@code em:{env}:c:layer:{route}:s{schema}:{data_version}:{normalized query}}. */
    String key(LayerRoute route, ReleaseRef release, String normalizedQuery);
}

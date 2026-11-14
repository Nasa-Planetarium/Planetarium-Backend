package io.emberline.harmonized.adapter.out.cache;

import io.emberline.harmonized.adapter.out.HarmonizedMetrics;
import io.emberline.harmonized.adapter.out.HarmonizedSettings;
import io.emberline.harmonized.application.port.out.AoiMonthCachePort;
import io.emberline.harmonized.domain.AoiMonths;
import io.emberline.harmonized.domain.MonthRecord;
import io.emberline.shared.release.ReleaseRef;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.TimeoutOptions;
import io.lettuce.core.api.StatefulRedisConnection;
import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * The per-AOI bundle in redis-cache (EMBERLINE_REDIS_CACHE_URL, LRU instance — never redis-durable). Own Lettuce
 * client, not a Spring bean, so it cannot replace the connection factory other modules use. Commands fail fast while
 * disconnected; after a failure Redis is skipped for {@code retry-after} and every request is answered from
 * PostgreSQL (redis-cache down ≠ API down). Entries are never deleted: the key carries method_version and data_version.
 */
@Component
class RedisAoiMonthCache implements AoiMonthCachePort {

    private static final Logger log = LoggerFactory.getLogger(RedisAoiMonthCache.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** Cached shape: bundle schema, aoi_id and the month rows. */
    record Bundle(int schema, String aoiId, List<MonthRecord> months) {
    }

    private final HarmonizedSettings.Cache settings;
    private final HarmonizedMetrics metrics;
    private RedisClient client;
    private StatefulRedisConnection<String, String> connection;
    private volatile long skipUntilNanos;

    RedisAoiMonthCache(HarmonizedSettings settings, HarmonizedMetrics metrics) {
        this.settings = settings.cache();
        this.metrics = metrics;
    }

    @Override
    public Optional<AoiMonths> get(ReleaseRef release, String aoiId, String route) {
        if (!usable()) {
            metrics.cache(route, "error");
            return Optional.empty();
        }
        try {
            String json = connection().sync().get(CacheKeys.calendarBundle(settings.env(), release, aoiId));
            if (json == null) {
                metrics.cache(route, "miss");
                return Optional.empty();
            }
            Bundle b = JSON.readValue(json, Bundle.class);
            if (b.schema() != CacheKeys.BUNDLE_SCHEMA || !aoiId.equals(b.aoiId())) {
                metrics.cache(route, "miss");
                return Optional.empty();
            }
            metrics.cache(route, "hit");
            return Optional.of(new AoiMonths(b.aoiId(), b.months()));
        } catch (RuntimeException e) {
            failed(e);
            metrics.cache(route, "error");
            return Optional.empty();
        }
    }

    @Override
    public void put(ReleaseRef release, AoiMonths months, String route) {
        if (!usable()) {
            return;
        }
        try {
            String json = JSON.writeValueAsString(new Bundle(CacheKeys.BUNDLE_SCHEMA, months.aoiId(), months.months()));
            connection().sync().set(CacheKeys.calendarBundle(settings.env(), release, months.aoiId()), json);
        } catch (RuntimeException e) {
            failed(e);
        }
    }

    private boolean usable() {
        return settings.enabled() && System.nanoTime() - skipUntilNanos >= 0;
    }

    private synchronized StatefulRedisConnection<String, String> connection() {
        if (connection == null || !connection.isOpen()) {
            if (client == null) {
                RedisURI uri = RedisURI.create(settings.url());
                uri.setTimeout(settings.commandTimeout());
                client = RedisClient.create(uri);
                client.setOptions(ClientOptions.builder()
                        .autoReconnect(true)
                        .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                        .socketOptions(SocketOptions.builder().connectTimeout(settings.connectTimeout()).build())
                        .timeoutOptions(TimeoutOptions.enabled(settings.commandTimeout()))
                        .build());
            }
            connection = client.connect();
        }
        return connection;
    }

    private void failed(RuntimeException e) {
        skipUntilNanos = System.nanoTime() + settings.retryAfter().toNanos();
        log.warn("redis-cache unavailable, answering from the database for {}: {}", settings.retryAfter(),
                e.getClass().getSimpleName());
    }

    @PreDestroy
    synchronized void close() {
        if (connection != null) {
            connection.close();
        }
        if (client != null) {
            client.shutdown();
        }
    }
}

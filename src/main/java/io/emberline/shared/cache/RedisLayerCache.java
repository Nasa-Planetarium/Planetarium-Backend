package io.emberline.shared.cache;

import io.emberline.shared.release.ReleaseRef;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.TimeoutOptions;
import io.lettuce.core.api.StatefulRedisConnection;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link LayerCache} on redis-cache, key {@code em:{env}:c:layer:{route}:s{schema}:{data_version}:{normalized query}}.
 * Own Lettuce client (not a Spring bean, so it cannot replace the connection factory other modules use), commands fail
 * fast while disconnected; after a failure Redis is skipped for {@code retry-after} and answers are computed (redis-cache
 * down ≠ API down). Values are the answer records as JSON (or the caller's own JSON text); entries are never deleted (no
 * TTL — the key changes with the release). Every layer — frp, satellite-status, fire-info, outlook — goes through this
 * one instance ({@code emberline.layer-cache}). Text answers of an outside service ({@link #findText}, forecast) use
 * the same key with the service's version in place of the data_version, are stored verbatim and, unlike the layer
 * answers, expire after the TTL their caller gives.
 */
@Component
class RedisLayerCache implements LayerCache {

    /** Bump when a cached answer record changes shape. */
    static final int SCHEMA = 1;
    private static final Logger log = LoggerFactory.getLogger(RedisLayerCache.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final LayerCacheSettings settings;
    private RedisClient client;
    private StatefulRedisConnection<String, String> connection;
    private volatile long skipUntilNanos;

    RedisLayerCache(LayerCacheSettings settings) {
        this.settings = settings;
    }

    static String key(String env, LayerRoute route, ReleaseRef release, String normalizedQuery) {
        return key(env, route, release.dataVersion(), normalizedQuery);
    }

    /** The one key builder: {@code version} = the release's data_version, or an outside service's ({@link #findText}). */
    static String key(String env, LayerRoute route, String version, String normalizedQuery) {
        return "em:" + env + ":c:layer:" + route.code() + ":s" + SCHEMA + ":" + version + ":" + normalizedQuery;
    }

    @Override
    public String key(LayerRoute route, ReleaseRef release, String normalizedQuery) {
        return key(settings.env(), route, release, normalizedQuery);
    }

    @Override
    public <T> T get(LayerRoute route, ReleaseRef release, String normalizedQuery, Class<T> type, Supplier<T> compute) {
        String key = key(route, release, normalizedQuery);
        String json = read(key);
        if (json != null) {
            try {
                return JSON.readValue(json, type);
            } catch (JacksonException e) {
                log.warn("layer cache entry of {} does not read as {}; computing it again", route.code(), type.getSimpleName());
            }
        }
        T answer = compute.get();
        write(key, () -> JSON.writeValueAsString(answer), null);
        return answer;
    }

    @Override
    public String json(LayerRoute route, ReleaseRef release, String normalizedQuery, Supplier<String> serialize) {
        String key = key(route, release, normalizedQuery);
        String json = read(key);
        if (json != null) {
            return json;
        }
        String made = serialize.get();
        write(key, () -> made, null);
        return made;
    }

    @Override
    public Optional<String> findText(LayerRoute route, String version, String normalizedQuery) {
        return Optional.ofNullable(read(key(settings.env(), route, version, normalizedQuery)));
    }

    @Override
    public void putText(LayerRoute route, String version, String normalizedQuery, String text, Duration ttl) {
        write(key(settings.env(), route, version, normalizedQuery), () -> text, ttl);
    }

    private String read(String key) {
        if (!usable()) {
            return null;
        }
        try {
            return connection().sync().get(key);
        } catch (RuntimeException e) {
            failed(e);
            return null;
        }
    }

    /** {@code ttl} null = no expiry (the layer answers). */
    private void write(String key, Supplier<String> json, Duration ttl) {
        if (!usable()) {
            return;
        }
        try {
            if (ttl == null) {
                connection().sync().set(key, json.get());
            } else {
                connection().sync().psetex(key, Math.max(1, ttl.toMillis()), json.get());
            }
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
        log.warn("redis-cache unavailable for layer answers, computing them for {}: {}", settings.retryAfter(),
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

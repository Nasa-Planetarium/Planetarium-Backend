package io.emberline.shared.cache;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code emberline.layer-cache.*}: the v1.2 layer answers in redis-cache (URL = EMBERLINE_REDIS_CACHE_URL, the LRU
 * instance — never redis-durable). Short connect timeout so a dead Redis costs one fast failure and then a back-off of
 * {@code retry-after}; the command timeout allows a large answer (an FRP page of 50,000 rows is a few MB).
 */
@ConfigurationProperties("emberline.layer-cache")
public record LayerCacheSettings(Boolean enabled, String url, String env, Duration connectTimeout, Duration commandTimeout,
                                 Duration retryAfter) {

    public LayerCacheSettings {
        enabled = enabled == null || enabled;
        url = url == null || url.isBlank() ? "redis://localhost:6379" : url;
        env = env == null || !env.matches("^[a-z0-9-]{1,32}$") ? "dev" : env;
        connectTimeout = connectTimeout == null ? Duration.ofMillis(300) : connectTimeout;
        commandTimeout = commandTimeout == null ? Duration.ofSeconds(2) : commandTimeout;
        retryAfter = retryAfter == null ? Duration.ofSeconds(5) : retryAfter;
    }
}

package io.emberline.harmonized.adapter.out;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code emberline.harmonized.*}: the redis-cache bundle (URL = EMBERLINE_REDIS_CACHE_URL, short timeouts so a dead
 * Redis costs one fast failure and then a back-off), the read-only DuckDB used on the release parquet files, and the
 * number of AOI cell sets kept in memory.
 */
@ConfigurationProperties("emberline.harmonized")
public record HarmonizedSettings(Cache cache, Duck duckdb, Integer cellSetEntries) {

    public HarmonizedSettings {
        cache = cache == null ? new Cache(true, null, null, null, null, null) : cache;
        duckdb = duckdb == null ? new Duck(null, 0) : duckdb;
        cellSetEntries = cellSetEntries == null || cellSetEntries < 1 ? 256 : cellSetEntries;
    }

    public record Cache(boolean enabled, String url, String env, Duration connectTimeout, Duration commandTimeout,
                        Duration retryAfter) {
        public Cache {
            url = url == null || url.isBlank() ? "redis://localhost:6379" : url;
            env = env == null || !env.matches("^[a-z0-9-]{1,32}$") ? "dev" : env;
            connectTimeout = connectTimeout == null ? Duration.ofMillis(300) : connectTimeout;
            commandTimeout = commandTimeout == null ? Duration.ofMillis(500) : commandTimeout;
            retryAfter = retryAfter == null ? Duration.ofSeconds(5) : retryAfter;
        }
    }

    public record Duck(String memoryLimit, int threads) {
        public Duck {
            memoryLimit = memoryLimit == null || !memoryLimit.matches("^[0-9]+(\\.[0-9]+)?\\s?(MB|GB|MiB|GiB)$") ? "512MB" : memoryLimit;
            threads = threads < 1 ? 2 : Math.min(threads, 64);
        }
    }
}

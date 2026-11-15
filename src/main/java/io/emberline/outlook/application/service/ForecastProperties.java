package io.emberline.outlook.application.service;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code emberline.forecast.*}: where the forecast service is and how long to wait for it, and the server-side
 * cache of its answers (in redis-cache through the shared layer cache, {@code emberline.layer-cache.*}).
 *
 * @param url base URL (EMBERLINE_FORECAST_URL), scheme://host[:port]; default the in-cluster Service
 * @param connectTimeout TCP connect limit
 * @param timeout whole-request limit (a 30-day query takes ≤ 0.3 s after warmup, a 13-month world or global
 * polygon query up to 6.7 s — measurement)
 * @param maxResponseBytes larger answers are refused as unavailable (a world query is tens of KB)
 * @param statusTtl how long one {@code /healthz} answer (data_version, as_of) is reused — a daily refresh
 * switches the cache key within this time
 * @param cacheTtl expiry of a stored answer (keys change with as_of anyway; the TTL bounds polygon keys)
 * @param daySkew a {@code horizon_days} answer is stored only if the KST date is the same this long before
 * the call and after it (clock difference between this pod and the service)
 */
@ConfigurationProperties("emberline.forecast")
public record ForecastProperties(String url, Duration connectTimeout, Duration timeout, Integer maxResponseBytes,
                                 Duration statusTtl, Duration cacheTtl, Duration daySkew) {

    public static final String DEFAULT_URL = "http://planetarium-forecast:8000";

    public ForecastProperties {
        url = url == null || url.isBlank() ? DEFAULT_URL : url.strip();
        connectTimeout = positive(connectTimeout, Duration.ofSeconds(1));
        timeout = positive(timeout, Duration.ofSeconds(10));
        maxResponseBytes = maxResponseBytes == null || maxResponseBytes < 1 ? 4 * 1024 * 1024 : maxResponseBytes;
        statusTtl = positive(statusTtl, Duration.ofSeconds(60));
        cacheTtl = positive(cacheTtl, Duration.ofHours(48));
        daySkew = daySkew == null || daySkew.isNegative() ? Duration.ofMinutes(2) : daySkew;
    }

    private static Duration positive(Duration d, Duration fallback) {
        return d == null || d.isZero() || d.isNegative() ? fallback : d;
    }
}

package io.emberline.platform.ratelimit;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code emberline.ratelimit.*}. Buckets refill intervally: {@code capacity} tokens at start, {@code refillTokens}
 * added every {@code refillPeriod} (never above capacity).
 *
 * @param enabled        off → no limiting (management port is never limited)
 * @param ip             scope ip: every public request costs 1 (default burst 100, 50 per second)
 * @param heavy          scope heavy: routes in {@code heavyCosts} (default 30 per minute)
 * @param heavyCosts     path → token cost of the heavy scope (canonical and alias paths)
 * @param trustedProxies peers (IPs or CIDRs) whose X-Forwarded-For is believed
 * @param hmacSecret     key of the subject HMAC in bucket keys (same on every pod)
 */
@ConfigurationProperties("emberline.ratelimit")
public record RateLimitProperties(Boolean enabled, Limit ip, Limit heavy, Map<String, Integer> heavyCosts,
                                  List<String> trustedProxies, String hmacSecret) {

    public RateLimitProperties {
        enabled = enabled == null || enabled;
        ip = ip == null ? new Limit(100L, 50L, Duration.ofSeconds(1)) : ip.orDefaults(100, 50, Duration.ofSeconds(1));
        heavy = heavy == null ? new Limit(30L, 30L, Duration.ofMinutes(1)) : heavy.orDefaults(30, 30, Duration.ofMinutes(1));
        heavyCosts = heavyCosts == null || heavyCosts.isEmpty() ? Map.of(
                "/api/v1/cells", 1, "/api/cells", 1,
                "/api/v1/hotspots", 1, "/api/hotspots", 1,
                "/api/v1/firms/wms-tiles", 1, "/api/firms/wms", 1) : Map.copyOf(heavyCosts);
        trustedProxies = trustedProxies == null ? List.of() : trustedProxies.stream().map(String::strip).filter(s -> !s.isEmpty()).toList();
        hmacSecret = hmacSecret == null || hmacSecret.isBlank() ? "emberline-rate-limit" : hmacSecret;
    }

    public record Limit(Long capacity, Long refillTokens, Duration refillPeriod) {

        Limit orDefaults(long c, long t, Duration p) {
            return new Limit(capacity == null || capacity < 1 ? c : capacity,
                    refillTokens == null || refillTokens < 1 ? t : refillTokens,
                    refillPeriod == null || refillPeriod.isZero() || refillPeriod.isNegative() ? p : refillPeriod);
        }
    }
}

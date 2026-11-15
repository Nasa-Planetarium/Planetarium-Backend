package io.emberline.platform.ratelimit;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * {@code emberline.ratelimit.*}. Buckets refill intervally: {@code capacity} tokens at start, {@code refillTokens}
 * added every {@code refillPeriod} (never above capacity).
 *
 * @param enabled off → no limiting (management port is never limited)
 * @param ip scope ip: every public request costs 1 (default burst 100, 50 per second)
 * @param heavy scope heavy: routes in {@code heavyCosts} (default 30 per minute)
 * @param heavyCosts path → token cost of the heavy scope (canonical and alias paths)
 * @param tiles scope tiles: routes in {@code tileCosts} (default 240 per minute — one map pan or zoom asks
 * for 12–20 FIRMS WMS tiles; the upstream key has its own budget, emberline.firms.budget-*)
 * @param tileCosts path → token cost of the tiles scope (canonical and alias paths)
 * @param trustedProxies peers (IPs or CIDRs) whose X-Forwarded-For is believed
 * @param hmacSecret key of the subject HMAC in bucket keys (same on every pod)
 * @param global scope global: every public request of all callers together, pod-local bucket (default burst
 * 2000, 700 per second) — bounds the origin load whatever the number of addresses
 * @param forecast scope forecast: routes in {@code forecastCosts} (default 10 per minute, stricter than heavy —
 * each request is a POST the CDN never caches and a call into the model's forecast service)
 * @param forecastCosts path → token cost of the forecast scope (canonical and alias paths)
 *
 * <p>The defaults are single-user values. The public deployment (about 200 reviewers behind one NAT address) sets
 * {@code ip}, {@code heavy}, {@code tiles} and {@code forecast} in deploy/k8s/planetarium-app.yaml (docs/DEVIATIONS.md
 * PUB-RL).
 */
@ConfigurationProperties("emberline.ratelimit")
public record RateLimitProperties(Boolean enabled, Limit ip, Limit heavy, Map<String, Integer> heavyCosts, Limit tiles,
                                  Map<String, Integer> tileCosts, List<String> trustedProxies, String hmacSecret,
                                  Limit global, Limit forecast, Map<String, Integer> forecastCosts) {

    @ConstructorBinding
    public RateLimitProperties {
        enabled = enabled == null || enabled;
        ip = ip == null ? new Limit(100L, 50L, Duration.ofSeconds(1)) : ip.orDefaults(100, 50, Duration.ofSeconds(1));
        heavy = heavy == null ? new Limit(30L, 30L, Duration.ofMinutes(1)) : heavy.orDefaults(30, 30, Duration.ofMinutes(1));
        heavyCosts = heavyCosts == null || heavyCosts.isEmpty() ? Map.of(
                "/api/v1/cells", 1, "/api/cells", 1,
                "/api/v1/hotspots", 1, "/api/hotspots", 1) : Map.copyOf(heavyCosts);
        tiles = tiles == null ? new Limit(240L, 240L, Duration.ofMinutes(1)) : tiles.orDefaults(240, 240, Duration.ofMinutes(1));
        tileCosts = tileCosts == null || tileCosts.isEmpty() ? Map.of(
                "/api/v1/firms/wms-tiles", 1, "/api/firms/wms", 1) : Map.copyOf(tileCosts);
        trustedProxies = trustedProxies == null ? List.of() : trustedProxies.stream().map(String::strip).filter(s -> !s.isEmpty()).toList();
        hmacSecret = hmacSecret == null || hmacSecret.isBlank() ? "emberline-rate-limit" : hmacSecret;
        global = global == null ? new Limit(2000L, 700L, Duration.ofSeconds(1)) : global.orDefaults(2000, 700, Duration.ofSeconds(1));
        forecast = forecast == null ? new Limit(10L, 10L, Duration.ofMinutes(1)) : forecast.orDefaults(10, 10, Duration.ofMinutes(1));
        forecastCosts = forecastCosts == null || forecastCosts.isEmpty() ? Map.of(
                "/api/v1/forecast", 1, "/api/forecast", 1) : Map.copyOf(forecastCosts);
    }

    public RateLimitProperties(Boolean enabled, Limit ip, Limit heavy, Map<String, Integer> heavyCosts, Limit tiles,
                               Map<String, Integer> tileCosts, List<String> trustedProxies, String hmacSecret) {
        this(enabled, ip, heavy, heavyCosts, tiles, tileCosts, trustedProxies, hmacSecret, null);
    }

    public RateLimitProperties(Boolean enabled, Limit ip, Limit heavy, Map<String, Integer> heavyCosts, Limit tiles,
                               Map<String, Integer> tileCosts, List<String> trustedProxies, String hmacSecret,
                               Limit global) {
        this(enabled, ip, heavy, heavyCosts, tiles, tileCosts, trustedProxies, hmacSecret, global, null, null);
    }

    public record Limit(Long capacity, Long refillTokens, Duration refillPeriod) {

        Limit orDefaults(long c, long t, Duration p) {
            return new Limit(capacity == null || capacity < 1 ? c : capacity,
                    refillTokens == null || refillTokens < 1 ? t : refillTokens,
                    refillPeriod == null || refillPeriod.isZero() || refillPeriod.isNegative() ? p : refillPeriod);
        }
    }
}

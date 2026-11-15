package io.emberline.platform.ratelimit;

import io.emberline.platform.redis.EventsProperties;
import io.emberline.shared.error.ErrorCode;
import io.emberline.shared.web.error.ProblemWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Public-chain abuse defence, before the security chain: scope {@code ip} on every /api request, then scope
 * {@code global} (all callers together, pod-local), then scope {@code heavy} on the routes of {@code heavy-costs},
 * scope {@code tiles} on the FIRMS WMS tile routes of {@code tile-costs} or scope {@code forecast} on the forecast
 * routes of {@code forecast-costs}. Per-caller buckets live on redis-durable
 * under {@code em:{env}:rl:{scope}:{hmac(subject)}} (the client address never appears in a key). Over the limit → 429
 * rate_limited (10301) with an integer Retry-After and the exhausted scope named in {@code detail}.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class RateLimitFilter extends OncePerRequestFilter {

    private final RateLimitProperties properties;
    private final EventsProperties keys;
    private final ClientKeyResolver clients;
    private final TokenBuckets buckets;
    private final ProblemWriter problems;

    public RateLimitFilter(RateLimitProperties properties, EventsProperties keys, ClientKeyResolver clients,
                           TokenBuckets buckets, ProblemWriter problems) {
        this.properties = properties;
        this.keys = keys;
        this.clients = clients;
        this.buckets = buckets;
        this.problems = problems;
    }

    /** A refused request: the exhausted scope and the whole seconds to wait. */
    public record Denial(String scope, long retryAfterSeconds) {
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !properties.enabled() || !path(request).startsWith("/api/") || "OPTIONS".equals(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Denial denial = check(request);
        if (denial != null) {
            response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(denial.retryAfterSeconds()));
            problems.write(request, response, ErrorCode.RATE_LIMITED, null, "too many requests (rate limit scope "
                    + denial.scope() + "); retry after " + denial.retryAfterSeconds() + " s");
            return;
        }
        chain.doFilter(request, response);
    }

    /** Takes the request's tokens scope by scope; the first scope that refuses ends the check. Null = allowed. */
    public Denial check(HttpServletRequest request) {
        String subject = clients.key(request);
        String path = path(request);
        TokenBuckets.Result r = take("ip", subject, 1, properties.ip());
        if (!r.granted()) {
            return new Denial("ip", r.retryAfterSeconds());
        }
        RateLimitProperties.Limit global = properties.global();
        r = buckets.takeLocal(keys.key("rl:global"), 1, global.capacity(), global.refillTokens(), global.refillPeriod());
        if (!r.granted()) {
            return new Denial("global", r.retryAfterSeconds());
        }
        for (RouteScope s : routeScopes()) {
            Integer cost = s.costs().get(path);
            if (cost != null) {
                r = take(s.name(), subject, cost, s.limit());
                return r.granted() ? null : new Denial(s.name(), r.retryAfterSeconds());
            }
        }
        return null;
    }

    /** A per-route scope: its name, the paths it covers with their token cost, and its bucket. */
    private record RouteScope(String name, Map<String, Integer> costs, RateLimitProperties.Limit limit) {
    }

    /** First matching scope wins; a path is listed in at most one of them by configuration. */
    private List<RouteScope> routeScopes() {
        return List.of(new RouteScope("heavy", properties.heavyCosts(), properties.heavy()),
                new RouteScope("tiles", properties.tileCosts(), properties.tiles()),
                new RouteScope("forecast", properties.forecastCosts(), properties.forecast()));
    }

    private TokenBuckets.Result take(String scope, String subject, long cost, RateLimitProperties.Limit limit) {
        return buckets.take(keys.key("rl:" + scope + ":" + subject), cost, limit.capacity(), limit.refillTokens(), limit.refillPeriod());
    }

    private static String path(HttpServletRequest request) {
        return request.getRequestURI().substring(request.getContextPath().length());
    }
}

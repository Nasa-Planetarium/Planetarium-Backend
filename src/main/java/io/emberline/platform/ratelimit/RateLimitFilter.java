package io.emberline.platform.ratelimit;

import io.emberline.platform.redis.EventsProperties;
import io.emberline.shared.error.ErrorCode;
import io.emberline.shared.web.error.ProblemWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Public-chain abuse defence, before the security chain: scope {@code ip} on every /api request, then scope
 * {@code global} (all callers together, pod-local), then scope {@code heavy} on the routes of {@code heavy-costs} or
 * scope {@code tiles} on the FIRMS WMS tile routes of {@code tile-costs}. Per-caller buckets live on redis-durable
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
        Integer heavyCost = properties.heavyCosts().get(path);
        Integer tileCost = properties.tileCosts().get(path);
        String scope = heavyCost != null ? "heavy" : tileCost != null ? "tiles" : null;
        if (scope != null) {
            r = take(scope, subject, heavyCost != null ? heavyCost : tileCost, heavyCost != null ? properties.heavy() : properties.tiles());
            if (!r.granted()) {
                return new Denial(scope, r.retryAfterSeconds());
            }
        }
        return null;
    }

    private TokenBuckets.Result take(String scope, String subject, long cost, RateLimitProperties.Limit limit) {
        return buckets.take(keys.key("rl:" + scope + ":" + subject), cost, limit.capacity(), limit.refillTokens(), limit.refillPeriod());
    }

    private static String path(HttpServletRequest request) {
        return request.getRequestURI().substring(request.getContextPath().length());
    }
}

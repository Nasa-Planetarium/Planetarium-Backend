package io.emberline.platform.ratelimit;

import io.emberline.platform.redis.EventsProperties;
import io.emberline.shared.error.ErrorCode;
import io.emberline.shared.web.error.ProblemWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Public-chain abuse defence, before the security chain: scope {@code ip} on every /api request and scope
 * {@code heavy} on the routes of {@code heavy-costs}. Buckets live on redis-durable under
 * {@code em:{env}:rl:{scope}:{hmac(subject)}} (the client address never appears in a key). Over the limit → 429
 * rate_limited (10301) with an integer Retry-After.
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
    private final SecretKeySpec hmacKey;

    public RateLimitFilter(RateLimitProperties properties, EventsProperties keys, ClientKeyResolver clients,
                           TokenBuckets buckets, ProblemWriter problems) {
        this.properties = properties;
        this.keys = keys;
        this.clients = clients;
        this.buckets = buckets;
        this.problems = problems;
        this.hmacKey = new SecretKeySpec(properties.hmacSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !properties.enabled() || !path(request).startsWith("/api/") || "OPTIONS".equals(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String subject = hmac(clients.subject(request));
        RateLimitProperties.Limit ip = properties.ip();
        TokenBuckets.Result r = buckets.take(keys.key("rl:ip:" + subject), 1, ip.capacity(), ip.refillTokens(), ip.refillPeriod());
        Integer cost = properties.heavyCosts().get(path(request));
        if (r.granted() && cost != null) {
            RateLimitProperties.Limit heavy = properties.heavy();
            r = buckets.take(keys.key("rl:heavy:" + subject), cost, heavy.capacity(), heavy.refillTokens(), heavy.refillPeriod());
        }
        if (!r.granted()) {
            response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(r.retryAfterSeconds()));
            problems.write(request, response, ErrorCode.RATE_LIMITED, null, "too many requests; retry after "
                    + r.retryAfterSeconds() + " s");
            return;
        }
        chain.doFilter(request, response);
    }

    private static String path(HttpServletRequest request) {
        return request.getRequestURI().substring(request.getContextPath().length());
    }

    private String hmac(String subject) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(hmacKey);
            return HexFormat.of().formatHex(mac.doFinal(subject.getBytes(StandardCharsets.UTF_8)), 0, 16);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }
}

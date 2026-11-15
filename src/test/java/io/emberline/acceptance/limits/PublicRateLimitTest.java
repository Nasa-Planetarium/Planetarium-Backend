package io.emberline.acceptance.limits;

import static org.assertj.core.api.Assertions.assertThat;

import io.emberline.platform.ratelimit.ClientKeyResolver;
import io.emberline.platform.ratelimit.RateLimitProperties;
import io.emberline.support.AppTestBase;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * pl-pub items 1 and 2 over HTTP: the test client (loopback) is a trusted proxy here, so X-Forwarded-For keys the
 * limiter per forwarded IP; scope global (capacity 20, refill 1 per hour) applies to all forwarded IPs together and its
 * 429 names the scope. /api/v1/version answers without a release, so no release is loaded.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PublicRateLimitTest extends AppTestBase {

    static final Redises REDIS = new Redises().start();
    static final String PATH = "/api/v1/version";

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.data.redis.url", REDIS::cacheUrl);
        r.add("emberline.events.durable-url", REDIS::durableUrl);
        r.add("emberline.ratelimit.enabled", () -> "true");
        r.add("emberline.ratelimit.ip.capacity", () -> "3");
        r.add("emberline.ratelimit.ip.refill-tokens", () -> "1");
        r.add("emberline.ratelimit.ip.refill-period", () -> "1h");
        r.add("emberline.ratelimit.global.capacity", () -> "20");
        r.add("emberline.ratelimit.global.refill-tokens", () -> "1");
        r.add("emberline.ratelimit.global.refill-period", () -> "1h");
        r.add("emberline.ratelimit.trusted-proxies", () -> "127.0.0.1,::1");
    }

    @AfterAll
    void stop() {
        REDIS.stop();
    }

    @Test
    @Order(1)
    void trustedProxyPlusForwardedIpKeysTheLimiterPerForwardedIp() {
        for (int i = 1; i <= 3; i++) {
            assertThat(get(PATH, "X-Forwarded-For", "198.51.100.1").statusCode()).as("A %d", i).isEqualTo(200);
        }
        HttpResponse<String> limited = get(PATH, "X-Forwarded-For", "198.51.100.1");
        assertThat(limited.statusCode()).isEqualTo(429);
        assertThat(json(limited).get("detail").asString()).contains("rate limit scope ip");
        assertThat(get(PATH, "X-Forwarded-For", "198.51.100.2").statusCode()).isEqualTo(200);
        // Spring reads X-Forwarded-For only; X-Client-IP is nginx's input, never a subject here
        assertThat(get(PATH, "X-Forwarded-For", "198.51.100.1", "X-Client-IP", "198.51.100.77").statusCode()).isEqualTo(429);
    }

    @Test
    @Order(2)
    void globalCapGives429NamingScopeGlobalForEveryAddress() {
        // 4 of the 20 global tokens went to the 4 granted requests above
        for (int i = 1; i <= 16; i++) {
            HttpResponse<String> r = get(PATH, "X-Forwarded-For", "203.0.113." + i);
            assertThat(r.statusCode()).as("address %d: %s", i, r.body()).isEqualTo(200);
        }
        for (int i = 17; i <= 20; i++) {
            HttpResponse<String> r = get(PATH, "X-Forwarded-For", "203.0.113." + i);
            assertThat(r.statusCode()).as("address %d", i).isEqualTo(429);
            assertThat(r.headers().firstValue("Content-Type").orElse("")).startsWith("application/problem+json");
            assertThat(r.headers().firstValue("Retry-After").orElse("")).matches("^[1-9][0-9]*$");
            assertThat(r.headers().firstValue("Cache-Control")).hasValue("no-store");
            JsonNode body = json(r);
            assertThat(body.get("code").asString()).isEqualTo("rate_limited");
            assertThat(body.get("error_no").asInt()).isEqualTo(10301);
            assertThat(body.get("detail").asString()).contains("rate limit scope global");
        }
    }

    @Test
    @Order(3)
    void untrustedPeerIgnoresEveryClientIpHeader() {
        ClientKeyResolver resolver = new ClientKeyResolver(new RateLimitProperties(true, null, null, null, null, null,
                List.of("10.42.0.0/24"), null));
        MockHttpServletRequest untrusted = new MockHttpServletRequest();
        untrusted.setRemoteAddr("192.0.2.7");
        untrusted.addHeader("X-Forwarded-For", "198.51.100.1");
        untrusted.addHeader("X-Client-IP", "198.51.100.2");
        untrusted.addHeader("CF-Connecting-IP", "198.51.100.3");
        untrusted.addHeader("X-Real-IP", "198.51.100.4");
        assertThat(resolver.subject(untrusted)).isEqualTo("192.0.2.7");

        MockHttpServletRequest nginx = new MockHttpServletRequest();
        nginx.setRemoteAddr("10.42.0.17");
        nginx.addHeader("X-Client-IP", "198.51.100.2");
        assertThat(resolver.subject(nginx)).as("trusted peer without X-Forwarded-For").isEqualTo("10.42.0.17");
        nginx.addHeader("X-Forwarded-For", "198.51.100.1");
        assertThat(resolver.subject(nginx)).isEqualTo("198.51.100.1");

        MockHttpServletRequest other = new MockHttpServletRequest();
        other.setRemoteAddr("10.42.0.17");
        other.addHeader("X-Forwarded-For", "198.51.100.9");
        assertThat(resolver.key(nginx)).matches("^[0-9a-f]{32}$").isNotEqualTo(resolver.key(other)).doesNotContain("198");
    }
}

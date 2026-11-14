package io.emberline.b4;

import static org.assertj.core.api.Assertions.assertThat;

import io.emberline.platform.ratelimit.ClientKeyResolver;
import io.emberline.platform.ratelimit.RateLimitProperties;
import io.emberline.support.AppTestBase;
import io.emberline.support.Fixtures;
import io.emberline.support.ReleaseFixtures;
import io.lettuce.core.api.sync.RedisCommands;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
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
 * B4.01–B4.05: ip bucket of capacity 5 (refill 1 per hour, so counts are exact), buckets on redis-durable, local
 * fallback when it is down. The test client is an untrusted peer (trusted proxies = 10.9.9.9 only).
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class B4RateLimitTest extends AppTestBase {

    static final Redises REDIS = new Redises().start();
    RedisCommands<String, String> durable;
    RedisCommands<String, String> cache;

    @DynamicPropertySource
    static void redis(DynamicPropertyRegistry r) {
        r.add("spring.data.redis.url", REDIS::cacheUrl);
        r.add("emberline.events.durable-url", REDIS::durableUrl);
        r.add("emberline.ratelimit.enabled", () -> "true");
        r.add("emberline.ratelimit.ip.capacity", () -> "5");
        r.add("emberline.ratelimit.ip.refill-tokens", () -> "1");
        r.add("emberline.ratelimit.ip.refill-period", () -> "1h");
        r.add("emberline.ratelimit.trusted-proxies", () -> "10.9.9.9");
        r.add("emberline.release.poll-interval", () -> "1s");
    }

    @BeforeAll
    void activate() throws Exception {
        Path root = ReleaseFixtures.newRoot();
        assertThat(Cli.run(jdbcUrl, root, REDIS.durableUrl(), "stage", "core")).isZero();
        assertThat(Cli.run(jdbcUrl, root, REDIS.durableUrl(), "activate", "core", "--expect-sha256", Fixtures.coreManifestSha256())).isZero();
        durable = Redises.client(REDIS.durableUrl());
        cache = Redises.client(REDIS.cacheUrl());
        Thread.sleep(1500);
    }

    @BeforeEach
    void freshBuckets() {
        durable.keys("em:test:rl:*").forEach(durable::del);
    }

    @AfterAll
    void stopRedis() {
        REDIS.stop();
    }

    @Test
    @Order(1)
    void b4_01_sixthRapidRequestIs429WithIntegerRetryAfter() {
        for (int i = 1; i <= 5; i++) {
            assertThat(get("/api/v1/aois").statusCode()).as("request %d", i).isEqualTo(200);
        }
        HttpResponse<String> r = get("/api/v1/aois");
        assertThat(r.statusCode()).isEqualTo(429);
        assertThat(r.headers().firstValue("Content-Type").orElse("")).startsWith("application/problem+json");
        assertThat(r.headers().firstValue("Retry-After").orElse("")).matches("^[1-9][0-9]*$");
        JsonNode body = json(r);
        assertThat(body.get("code").asString()).isEqualTo("rate_limited");
        assertThat(body.get("error_no").asInt()).isEqualTo(10301);
        assertThat(r.headers().firstValue("X-Request-Id")).isPresent();
    }

    @Test
    @Order(2)
    void b4_02_untrustedPeerCannotEscapeByChangingXForwardedFor() {
        for (int i = 1; i <= 5; i++) {
            assertThat(get("/api/v1/aois", "X-Forwarded-For", "198.51.100." + i).statusCode()).isEqualTo(200);
        }
        assertThat(get("/api/v1/aois", "X-Forwarded-For", "198.51.100.99").statusCode()).isEqualTo(429);
        assertThat(durable.keys("em:test:rl:ip:*")).hasSize(1);
    }

    @Test
    @Order(3)
    void b4_03_trustedProxyIpv6ClientsFoldToTheirSlash64() {
        ClientKeyResolver resolver = new ClientKeyResolver(new RateLimitProperties(true, null, null, null, null, null,
                List.of("10.9.9.9", "fd00::/8"), null));
        String a = resolver.subject(fromProxy("2001:db8::1"));
        String b = resolver.subject(fromProxy("2001:db8::ffff"));
        String c = resolver.subject(fromProxy("2001:db9::1"));
        assertThat(a).isEqualTo(b).isEqualTo("2001:db8:0:0::/64");
        assertThat(c).isNotEqualTo(a);
        // right-most untrusted hop wins; a spoofed left-most entry is ignored
        assertThat(resolver.subject(fromProxy("6.6.6.6, 2001:db8::5, fd00::1"))).isEqualTo(a);
        MockHttpServletRequest untrusted = new MockHttpServletRequest();
        untrusted.setRemoteAddr("192.0.2.7");
        untrusted.addHeader("X-Forwarded-For", "2001:db8::1");
        assertThat(resolver.subject(untrusted)).isEqualTo("192.0.2.7");
    }

    static MockHttpServletRequest fromProxy(String xff) {
        MockHttpServletRequest r = new MockHttpServletRequest();
        r.setRemoteAddr("10.9.9.9");
        r.addHeader("X-Forwarded-For", xff);
        return r;
    }

    @Test
    @Order(4)
    void b4_04_bucketsLiveOnRedisDurableEvenWhenRedisCacheIsFull() {
        String junk = "x".repeat(10_000);
        for (int i = 0; i < 1_000; i++) {
            cache.set("junk:" + i, junk);
        }
        for (int i = 1; i <= 5; i++) {
            get("/api/v1/aois");
        }
        assertThat(get("/api/v1/aois").statusCode()).isEqualTo(429);
        List<String> keys = durable.keys("em:test:rl:ip:*");
        assertThat(keys).hasSize(1);
        assertThat(cache.exists(keys.get(0))).isZero();
        assertThat(cache.keys("em:test:rl:*")).isEmpty();
        assertThat(durable.ttl(keys.get(0))).isPositive();
    }

    @Test
    @Order(5)
    void b4_05_publicReadsSurviveRedisDurableStopping() throws Exception {
        REDIS.stopDurable();
        try {
            long start = System.nanoTime();
            HttpResponse<String> r = get("/api/v1/aois/KR");
            assertThat(r.statusCode()).isEqualTo(200);
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
            assertThat(get("/api/v1/cells?aoi_id=KR&month=2025-03").statusCode()).isEqualTo(200);
        } finally {
            REDIS.startDurable();
        }
    }
}

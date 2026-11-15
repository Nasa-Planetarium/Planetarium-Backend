package io.emberline.acceptance.s4limits;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import io.emberline.support.AppTestBase;
import io.emberline.support.Fixtures;
import io.emberline.support.ReleaseFixtures;
import io.lettuce.core.api.sync.RedisCommands;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * pl-fix2 item 3: the FIRMS WMS tile routes have their own scope {@code tiles} (default 240 per minute) instead of
 * {@code heavy} (30 per minute, still /cells and /hotspots). Scopes tiles and heavy keep their defaults here; the ip
 * scope is raised so that 241 requests within a second measure the tiles bucket, not the ip one. Fake upstream and
 * fake key file as in FirmsWmsTest; the upstream budget is far above what the test asks for.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TilesRateLimitTest extends AppTestBase {

    static final Redises REDIS = new Redises().start();
    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n', 1, 2, 3, 4};
    static final AtomicInteger UPSTREAM_CALLS = new AtomicInteger();
    static final HttpServer UPSTREAM = upstream();
    static final String TILE = "/api/v1/firms/wms-tiles?layer=viirs_noaa20_24h&bbox=126,34,130,38&height=256&width=";

    RedisCommands<String, String> durable;
    RedisCommands<String, String> cache;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.data.redis.url", REDIS::cacheUrl);
        r.add("emberline.events.durable-url", REDIS::durableUrl);
        r.add("emberline.firms.wms-base-url", () -> "http://127.0.0.1:" + UPSTREAM.getAddress().getPort());
        r.add("emberline.firms.map-key-file", TilesRateLimitTest::keyFile);
        r.add("emberline.ratelimit.enabled", () -> "true");
        r.add("emberline.ratelimit.ip.capacity", () -> "100000");
        r.add("emberline.ratelimit.ip.refill-tokens", () -> "100000");
    }

    static String keyFile() {
        try {
            Path dir = Files.createTempDirectory("emberline-firms");
            return Files.writeString(dir.resolve("key"), "FAKEKEY0123456789abcdef0123456789\n").toString();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static HttpServer upstream() {
        try {
            HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            s.createContext("/", ex -> {
                UPSTREAM_CALLS.incrementAndGet();
                ex.getResponseHeaders().set("Content-Type", "image/png");
                ex.sendResponseHeaders(200, PNG.length);
                ex.getResponseBody().write(PNG);
                ex.close();
            });
            s.start();
            return s;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @BeforeAll
    void activate() {
        Path root = ReleaseFixtures.newRoot();
        assertThat(Cli.run(jdbcUrl, root, REDIS.durableUrl(), "stage", "core")).isZero();
        assertThat(Cli.run(jdbcUrl, root, REDIS.durableUrl(), "activate", "core", "--expect-sha256", Fixtures.coreManifestSha256())).isZero();
        durable = Redises.client(REDIS.durableUrl());
        cache = Redises.client(REDIS.cacheUrl());
    }

    @BeforeEach
    void freshBuckets() {
        cache.flushall();
        durable.keys("em:test:budget:*").forEach(durable::del);
        durable.keys("em:test:rl:*").forEach(durable::del);
    }

    @AfterAll
    void stop() {
        UPSTREAM.stop(0);
        REDIS.stop();
    }

    /** A map pan: 20 distinct tiles (upstream misses), then the same 20 again (cache hits) — 40 requests. */
    @Test
    @Order(1)
    void fortyTileRequestsInAMinuteFromOneIpAllPass() {
        int calls = UPSTREAM_CALLS.get();
        for (int i = 0; i < 40; i++) {
            String path = (i % 2 == 0 ? TILE : TILE.replace("/api/v1/firms/wms-tiles", "/api/firms/wms")) + (256 + i % 20);
            HttpResponse<String> r = get(path);
            assertThat(r.statusCode()).as("tile request %d: %s", i + 1, r.body()).isEqualTo(200);
            assertThat(r.headers().firstValue("Content-Type").orElse("")).startsWith("image/png");
        }
        assertThat(UPSTREAM_CALLS.get() - calls).isPositive().isLessThanOrEqualTo(20);
        // the tiles did not spend the heavy bucket: /cells still has its 30
        for (int i = 1; i <= 30; i++) {
            assertThat(get("/api/v1/cells?aoi_id=KR&month=2025-03").statusCode()).as("cells %d", i).isEqualTo(200);
        }
    }

    @Test
    @Order(2)
    void tile241InAMinuteIs429NamingScopeTiles() {
        for (int i = 1; i <= 240; i++) {
            HttpResponse<String> r = get(TILE + (256 + i % 20));
            assertThat(r.statusCode()).as("tile request %d: %s", i, r.body()).isEqualTo(200);
        }
        HttpResponse<String> limited = get(TILE + "256");
        assertThat(limited.statusCode()).isEqualTo(429);
        assertThat(limited.headers().firstValue("Content-Type").orElse("")).startsWith("application/problem+json");
        assertThat(limited.headers().firstValue("Retry-After").orElse("")).matches("^[1-9][0-9]*$");
        JsonNode body = json(limited);
        assertThat(body.get("code").asString()).isEqualTo("rate_limited");
        assertThat(body.get("error_no").asInt()).isEqualTo(10301);
        assertThat(body.get("detail").asString()).contains("rate limit scope tiles");
        // the alias path shares the bucket; heavy routes are unaffected
        assertThat(get(TILE.replace("/api/v1/firms/wms-tiles", "/api/firms/wms") + "256").statusCode()).isEqualTo(429);
        assertThat(get("/api/v1/cells?aoi_id=KR&month=2025-03").statusCode()).isEqualTo(200);
        assertThat(durable.keys("em:test:rl:tiles:*")).hasSize(1);
    }

    @Test
    @Order(3)
    void cellsStillHitHeavyAt31() {
        for (int i = 1; i <= 30; i++) {
            HttpResponse<String> r = get(i % 2 == 0 ? "/api/v1/cells?aoi_id=KR&month=2025-03" : "/api/cells?aoi_id=KR&year=2025&month=3");
            assertThat(r.statusCode()).as("cells %d: %s", i, r.body()).isEqualTo(200);
        }
        HttpResponse<String> limited = get("/api/v1/cells?aoi_id=KR&month=2025-03");
        assertThat(limited.statusCode()).isEqualTo(429);
        JsonNode body = json(limited);
        assertThat(body.get("code").asString()).isEqualTo("rate_limited");
        assertThat(body.get("detail").asString()).contains("rate limit scope heavy");
        assertThat(get("/api/v1/hotspots?bbox=126,34,127,35&start=2025-03-01&end=2025-03-02").statusCode()).isEqualTo(429);
        // tiles keep their own bucket while heavy is empty
        assertThat(get(TILE + "256").statusCode()).isEqualTo(200);
    }
}

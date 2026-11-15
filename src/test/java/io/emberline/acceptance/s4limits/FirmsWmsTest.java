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
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * FIRMS WMS proxy against a mock upstream (in-test HTTP server) with a fake key file. Global budget 3
 * per hour; every test starts with empty redis-cache and redis-durable (fresh budget, no cached tiles).
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(OutputCaptureExtension.class)
class FirmsWmsTest extends AppTestBase {

    static final Redises REDIS = new Redises().start();
    static final String KEY = "FAKEKEY0123456789abcdef0123456789";
    static final Path KEY_FILE = keyFile();
    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n', 1, 2, 3, 4};
    static final List<String> SEEN = new CopyOnWriteArrayList<>();
    static final List<String> HOSTS = new CopyOnWriteArrayList<>();
    static final AtomicReference<String> MODE = new AtomicReference<>("ok");
    static final HttpServer UPSTREAM = upstream();
    static final String T = "/api/v1/firms/wms-tiles?layer=viirs_noaa20_24h&bbox=126,34,130,38&width=256&height=256";

    RedisCommands<String, String> durable;
    RedisCommands<String, String> cache;
    final List<HttpResponse<String>> responses = new ArrayList<>();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.data.redis.url", REDIS::cacheUrl);
        r.add("emberline.events.durable-url", REDIS::durableUrl);
        r.add("emberline.firms.wms-base-url", () -> "http://127.0.0.1:" + UPSTREAM.getAddress().getPort());
        r.add("emberline.firms.map-key-file", KEY_FILE::toString);
        r.add("emberline.firms.budget-capacity", () -> "3");
        r.add("emberline.firms.budget-period", () -> "1h");
        r.add("emberline.firms.timeout", () -> "1s");
        r.add("emberline.ratelimit.enabled", () -> "true");
        r.add("emberline.ratelimit.heavy.capacity", () -> "12");
        r.add("emberline.ratelimit.heavy.refill-tokens", () -> "1");
        r.add("emberline.ratelimit.heavy.refill-period", () -> "1h");
        // pl-fix2: the tile routes are limited by scope tiles, no longer heavy
        r.add("emberline.ratelimit.tiles.capacity", () -> "12");
        r.add("emberline.ratelimit.tiles.refill-tokens", () -> "1");
        r.add("emberline.ratelimit.tiles.refill-period", () -> "1h");
    }

    static Path keyFile() {
        try {
            Path dir = Files.createTempDirectory("emberline-firms");
            return Files.writeString(dir.resolve("key"), KEY + "\n");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static HttpServer upstream() {
        try {
            HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            s.createContext("/", ex -> {
                SEEN.add(ex.getRequestURI().toString());
                HOSTS.add(ex.getRequestHeaders().getFirst("Host"));
                String mode = MODE.get();
                if (mode.equals("timeout")) {
                    try {
                        Thread.sleep(2500);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                if (mode.equals("500")) {
                    byte[] body = ("upstream failure for key " + KEY).getBytes(StandardCharsets.UTF_8);
                    ex.sendResponseHeaders(500, body.length);
                    ex.getResponseBody().write(body);
                } else {
                    ex.getResponseHeaders().set("Content-Type", "image/png");
                    ex.sendResponseHeaders(200, PNG.length);
                    ex.getResponseBody().write(PNG);
                }
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
    void fresh() {
        cache.flushall();
        durable.keys("em:test:budget:*").forEach(durable::del);
        durable.keys("em:test:rl:*").forEach(durable::del);
        MODE.set("ok");
    }

    @AfterAll
    void stop() {
        UPSTREAM.stop(0);
        REDIS.stop();
    }

    HttpResponse<byte[]> bytes(String path) {
        try {
            HttpResponse<byte[]> r = HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                    .timeout(Duration.ofSeconds(30)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            responses.add(asText(r));
            return r;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    HttpResponse<String> text(String path) {
        HttpResponse<String> r = get(path);
        responses.add(r);
        return r;
    }

    static HttpResponse<String> asText(HttpResponse<byte[]> r) {
        String body = new String(r.body(), StandardCharsets.ISO_8859_1);
        return new HttpResponse<>() {
            public int statusCode() { return r.statusCode(); }
            public HttpRequest request() { return r.request(); }
            public java.util.Optional<HttpResponse<String>> previousResponse() { return java.util.Optional.empty(); }
            public java.net.http.HttpHeaders headers() { return r.headers(); }
            public String body() { return body; }
            public java.util.Optional<javax.net.ssl.SSLSession> sslSession() { return java.util.Optional.empty(); }
            public URI uri() { return r.uri(); }
            public java.net.http.HttpClient.Version version() { return r.version(); }
        };
    }

    @Test
    @Order(1)
    void b4_12_allowedParametersGivePngAndExtraOnesAre400() {
        HttpResponse<byte[]> r = bytes(T);
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.headers().firstValue("Content-Type").orElse("")).startsWith("image/png");
        assertThat(r.body()).isEqualTo(PNG);
        assertThat(r.headers().firstValue("Cache-Control")).hasValue("public, max-age=60, s-maxage=60");
        HttpResponse<String> extra = text(T + "&foo=1");
        assertThat(extra.statusCode()).isEqualTo(400);
        assertThat(json(extra).get("code").asString()).isEqualTo("invalid_param");
        assertThat(json(extra).get("param").asString()).isEqualTo("foo");
        assertThat(json(text(T.replace("viirs_noaa20_24h", "secret_layer"))).get("param").asString()).isEqualTo("layer");
        assertThat(json(text(T.replace("width=256", "width=4096"))).get("param").asString()).isEqualTo("width");
        assertThat(json(text(T.replace("126,34,130,38", "0,0,10,10"))).get("param").asString()).isEqualTo("bbox");
    }

    @Test
    @Order(2)
    void b4_13_secondIdenticalRequestIsServedFromRedisCache() {
        int before = SEEN.size();
        byte[] first = bytes(T + "&time=2025-03-21").body();
        byte[] second = bytes(T + "&time=2025-03-21").body();
        assertThat(first).isEqualTo(PNG).isEqualTo(second);
        assertThat(SEEN.size() - before).isEqualTo(1);
        assertThat(cache.keys("em:test:wms:*")).hasSize(1);
        assertThat(SEEN.get(SEEN.size() - 1)).contains("LAYERS=fires_viirs_noaa20_24").contains("BBOX=126%2C34%2C130%2C38")
                .contains("TIME=2025-03-21").startsWith("/mapserver/wms/fires/" + KEY + "/?");
    }

    @Test
    @Order(3)
    void b4_14_ssrfParameterIs400AndUpstreamHostIsFixed() {
        HttpResponse<String> r = text(T + "&url=http://127.0.0.1:6379");
        assertThat(r.statusCode()).isEqualTo(400);
        assertThat(json(r).get("param").asString()).isEqualTo("url");
        bytes(T.replace("width=256", "width=300"));
        assertThat(HOSTS).isNotEmpty().allMatch(h -> h.equals("127.0.0.1:" + UPSTREAM.getAddress().getPort()));
    }

    @Test
    @Order(4)
    void b4_15_upstream500Is502WithoutTheUpstreamBody() {
        MODE.set("500");
        HttpResponse<String> r = text(T);
        assertThat(r.statusCode()).isEqualTo(502);
        assertThat(json(r).get("code").asString()).isEqualTo("upstream_error");
        assertThat(json(r).get("error_no").asInt()).isEqualTo(60501);
        assertThat(r.body()).doesNotContain("upstream failure").doesNotContain(KEY);
    }

    @Test
    @Order(5)
    void b4_18_budgetOfThreeCacheMissesThen503WhileCachedTilesServe() {
        assertThat(bytes(T).statusCode()).isEqualTo(200);
        assertThat(bytes(T.replace("width=256", "width=257")).statusCode()).isEqualTo(200);
        assertThat(bytes(T.replace("width=256", "width=258")).statusCode()).isEqualTo(200);
        HttpResponse<String> fourth = text(T.replace("width=256", "width=259"));
        assertThat(fourth.statusCode()).isEqualTo(503);
        assertThat(json(fourth).get("code").asString()).isEqualTo("upstream_unavailable");
        assertThat(fourth.headers().firstValue("Retry-After").orElse("")).matches("^[1-9][0-9]*$");
        assertThat(bytes(T).statusCode()).isEqualTo(200);
        assertThat(durable.keys("em:test:budget:*")).hasSize(1);
        assertThat(cache.keys("em:test:budget:*")).isEmpty();
    }

    @Test
    @Order(6)
    void b4_19_aliasGivesTheSameBytes() {
        byte[] canonical = bytes(T).body();
        HttpResponse<byte[]> alias = bytes(T.replace("/api/v1/firms/wms-tiles", "/api/firms/wms"));
        assertThat(alias.statusCode()).isEqualTo(200);
        assertThat(alias.body()).isEqualTo(canonical);
        assertThat(alias.headers().firstValue("Link").orElse("")).contains("/api/v1/firms/wms-tiles");
    }

    @Test
    @Order(7)
    void b4_16_theKeyAppearsNowhere(CapturedOutput output) {
        MODE.set("500");
        text(T);
        MODE.set("timeout");
        HttpResponse<String> timeout = text(T.replace("width=256", "width=260"));
        assertThat(timeout.statusCode()).isEqualTo(503);
        MODE.set("ok");
        text("/api/v1/firms/wms-tiles?layer=viirs_noaa20_24h");
        text(T.replace("width=256", "width=261"));
        assertThat(text(T.replace("width=256", "width=262")).statusCode()).isEqualTo(503);
        HttpResponse<String> limited = null;
        for (int i = 0; i < 20 && (limited == null || limited.statusCode() != 429); i++) {
            limited = text(T.replace("width=256", "width=261"));
        }
        assertThat(limited.statusCode()).isEqualTo(429);
        org.slf4j.LoggerFactory.getLogger("probe").warn("probe line with {}", KEY);
        for (HttpResponse<String> r : responses) {
            assertThat(r.body()).doesNotContain(KEY);
            assertThat(r.headers().map().toString()).doesNotContain(KEY);
        }
        assertThat(output.getAll()).doesNotContain(KEY).contains("probe line with ****");
        for (RedisCommands<String, String> redis : List.of(cache, durable)) {
            assertThat(redis.keys("*")).noneMatch(k -> k.contains(KEY));
        }
    }

    @Test
    @Order(8)
    void b4_17_withoutKeyFileTheProxyIs503AndTheRestServes() throws IOException {
        Files.delete(KEY_FILE);
        HttpResponse<String> r = text(T);
        assertThat(r.statusCode()).isEqualTo(503);
        assertThat(json(r).get("code").asString()).isEqualTo("upstream_unavailable");
        assertThat(json(r).get("error_no").asInt()).isEqualTo(60502);
        assertThat(text("/api/v1/aois").statusCode()).isEqualTo(200);
    }
}

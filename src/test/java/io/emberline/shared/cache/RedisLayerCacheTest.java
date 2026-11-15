package io.emberline.shared.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.emberline.shared.release.ReleaseRef;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import redis.embedded.RedisServer;

/**
 * F4: one layer cache for every v1.2 layer. Keys = route + data_version + normalized query and no two
 * layers share a key space: the route segment is ':'-free and unique, so a key names its route; each route is used by one
 * module only. Against a real redis-server: a typed answer and a caller-serialized body are written once and read back on
 * a hit; a failed computation stores nothing. the forecast route keys text answers by the
 * forecast service's own version (data_version@as_of) in the same key shape; they carry their caller's TTL.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RedisLayerCacheTest {

    static final ReleaseRef A = new ReleaseRef(1L, "v1.2-2026-11-15", "v1-m2v-2026-11-14", "v1.2-2026-11-15#0123456789ab", Instant.EPOCH);
    static final ReleaseRef B = new ReleaseRef(2L, "v1.2-2026-11-15-r2", "v1-m2v-2026-11-14", "v1.2-2026-11-15-r2#ba9876543210", Instant.EPOCH);
    static final List<String> QUERIES = List.of("", "aoi_id=KR", "aoi_id=KR&date=&start=&end=", "bbox=&min_p_any=0.0&fields=map",
            "parent_id=KR", "s1:x", "fwi?aoi_id=KR");
    static final Pattern KEY = Pattern.compile("^em:([a-z0-9-]+):c:layer:([a-z][a-z0-9-]*):s(\\d+):([^:]+#[0-9a-f]{12}):(.*)$");
    /** Forecast service versions as ForecastService builds them: {@code /healthz} data_version (shape) @ as_of. */
    static final List<String> FORECAST_VERSIONS = List.of(
            "v1.2-2026-11-15-r3#7691aa4020b8+planner.2026-11-14@2026-11-14",
            "v1.2-2026-11-15-r3#7691aa4020b8+planner.2026-11-15@2026-11-15",
            "v1.2-2026-11-15-r3#f5de697a3838+planner.2026-11-14@2026-11-14");
    static final List<String> FORECAST_QUERIES = List.of(
            "kst2026-11-15:" + "0".repeat(64), "kst2026-11-16:" + "0".repeat(64), "0".repeat(64), "f".repeat(64));
    static final Pattern TEXT_KEY = Pattern.compile("^em:([a-z0-9-]+):c:layer:([a-z][a-z0-9-]*):s(\\d+):([^:]+):(.*)$");

    final int port = freePort();
    final RedisServer server = start(port);

    record Answer(String aoiId, List<Double> values, Integer n) {
    }

    @AfterAll
    void stop() throws IOException {
        server.stop();
    }

    @Test
    void routeCodesAreDistinctAndColonFree() {
        Set<String> codes = new HashSet<>();
        for (LayerRoute r : LayerRoute.values()) {
            assertThat(r.code()).matches(LayerRoute.CODE).doesNotContain(":");
            assertThat(codes.add(r.code())).as(r.code()).isTrue();
        }
        assertThat(codes).contains("frp", "satellite-status", "fire-events", "fire-event", "fwi", "spread-nowcast", "fire-regime",
                "occurrence-grid", "occurrence-aois", "aoi-occurrence", "forecast");
    }

    /** Every (route, release, query) has its own key, and the key parses back to exactly that route, release and query. */
    @Test
    void keysOfDifferentLayersNeverCollide() {
        Map<String, String> seen = new TreeMap<>();
        for (LayerRoute route : LayerRoute.values()) {
            for (ReleaseRef release : List.of(A, B)) {
                for (String q : QUERIES) {
                    String key = RedisLayerCache.key("test", route, release, q);
                    String what = route + " " + release.dataVersion() + " " + q;
                    assertThat(seen.put(key, what)).as("key %s of %s", key, what).isNull();
                    Matcher m = KEY.matcher(key);
                    assertThat(m.matches()).as(key).isTrue();
                    assertThat(m.group(2)).isEqualTo(route.code());
                    assertThat(m.group(3)).isEqualTo(Integer.toString(RedisLayerCache.SCHEMA));
                    assertThat(m.group(4)).isEqualTo(release.dataVersion());
                    assertThat(m.group(5)).isEqualTo(q);
                }
            }
        }
        for (String version : FORECAST_VERSIONS) {
            for (String q : FORECAST_QUERIES) {
                String key = RedisLayerCache.key("test", LayerRoute.FORECAST, version, q);
                String what = "forecast text " + version + " " + q;
                assertThat(seen.put(key, what)).as("key %s of %s", key, what).isNull();
                Matcher m = TEXT_KEY.matcher(key);
                assertThat(m.matches()).as(key).isTrue();
                assertThat(m.group(2)).isEqualTo(LayerRoute.FORECAST.code());
                assertThat(m.group(3)).isEqualTo(Integer.toString(RedisLayerCache.SCHEMA));
                assertThat(m.group(4)).isEqualTo(version);
                assertThat(m.group(5)).isEqualTo(q);
            }
        }
        assertThat(seen).hasSize(LayerRoute.values().length * 2 * QUERIES.size() + FORECAST_VERSIONS.size() * FORECAST_QUERIES.size());
        for (LayerRoute a : LayerRoute.values()) {
            for (LayerRoute b : LayerRoute.values()) {
                String prefixA = "em:test:c:layer:" + a.code() + ":";
                assertThat(a != b && RedisLayerCache.key("test", b, A, "q").startsWith(prefixA)).as(a + " vs " + b).isFalse();
                assertThat(a != b && RedisLayerCache.key("test", b, FORECAST_VERSIONS.get(0), "q").startsWith(prefixA))
                        .as("text " + a + " vs " + b).isFalse();
            }
        }
    }

    /** Each route constant is used by the main code of exactly one module, so two layers never write one key space. */
    @Test
    void eachRouteBelongsToOneModule() throws IOException {
        Pattern use = Pattern.compile("LayerRoute\\.([A-Z_]+)");
        Map<String, Set<String>> modules = new TreeMap<>();
        Path main = Path.of("src/main/java/io/emberline");
        try (Stream<Path> files = Files.walk(main)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String module = main.relativize(f).getName(0).toString();
                if (module.equals("shared")) {
                    continue;
                }
                Matcher m = use.matcher(Files.readString(f));
                while (m.find()) {
                    modules.computeIfAbsent(m.group(1), k -> new TreeSet<>()).add(module);
                }
            }
        }
        assertThat(modules.keySet()).containsExactlyInAnyOrderElementsOf(Arrays.stream(LayerRoute.values()).map(Enum::name).toList());
        modules.forEach((route, users) -> assertThat(users).as(route).hasSize(1));
        assertThat(modules.get("FRP")).containsExactly("observation");
        assertThat(modules.get("SATELLITE_STATUS")).containsExactly("provenance");
        assertThat(modules.get("FWI")).containsExactly("fireinfo");
        assertThat(modules.get("OCCURRENCE_GRID")).containsExactly("outlook");
        assertThat(modules.get("FORECAST")).containsExactly("outlook");
    }

    @Test
    void anAnswerIsWrittenOnceAndReadBackOnAHit() {
        RedisLayerCache cache = cache("rt");
        try {
            AtomicInteger computed = new AtomicInteger();
            Answer answer = new Answer("KR", List.of(0.25, 1.0E-5), null);
            for (int i = 0; i < 3; i++) {
                assertThat(cache.get(LayerRoute.FWI, A, "aoi_id=KR", Answer.class, () -> {
                    computed.incrementAndGet();
                    return answer;
                })).isEqualTo(answer);
            }
            assertThat(computed).hasValue(1);
            assertThat(cache.get(LayerRoute.FWI, B, "aoi_id=KR", Answer.class, () -> new Answer("B", List.of(), 1)).aoiId())
                    .as("another data_version is another key").isEqualTo("B");
            assertThat(cache.get(LayerRoute.FIRE_REGIME, A, "aoi_id=KR", Answer.class, () -> new Answer("R", List.of(), 2)).aoiId())
                    .as("another route is another key").isEqualTo("R");

            List<String> bodies = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                bodies.add(cache.json(LayerRoute.OCCURRENCE_AOIS, A, "parent_id=KR", () -> {
                    computed.incrementAndGet();
                    return "{\"parent_id\":\"KR\",\"count\":252}";
                }));
            }
            assertThat(bodies).containsOnly("{\"parent_id\":\"KR\",\"count\":252}");
            assertThat(computed).hasValue(2);
            assertThat(cache.json(LayerRoute.OCCURRENCE_AOIS, A, "parent_id=KR", () -> {
                throw new AssertionError("miss");
            })).isEqualTo("{\"parent_id\":\"KR\",\"count\":252}");
        } finally {
            cache.close();
        }
    }

    /** a text answer is stored verbatim with its TTL, found under its own version only, and is no layer answer. */
    @Test
    void aTextAnswerIsFoundUnderItsVersionAndExpires() throws InterruptedException {
        RedisLayerCache cache = cache("txt");
        try {
            String v1 = FORECAST_VERSIONS.get(0);
            String q = FORECAST_QUERIES.get(0);
            String body = "{\"as_of\": \"2026-11-14\", \"display_hint\": \"거의 확실히 탐지됨\", \"p_any\": 1e-07}";
            assertThat(cache.findText(LayerRoute.FORECAST, v1, q)).isEmpty();
            cache.putText(LayerRoute.FORECAST, v1, q, body, Duration.ofHours(48));
            assertThat(cache.findText(LayerRoute.FORECAST, v1, q)).contains(body);
            assertThat(cache.findText(LayerRoute.FORECAST, FORECAST_VERSIONS.get(1), q)).as("another as_of").isEmpty();
            assertThat(cache.findText(LayerRoute.FORECAST, FORECAST_VERSIONS.get(2), q)).as("another data_version").isEmpty();
            assertThat(cache.findText(LayerRoute.FORECAST, v1, FORECAST_QUERIES.get(1))).as("another KST day").isEmpty();
            assertThat(cache.findText(LayerRoute.OCCURRENCE_GRID, v1, q)).as("another route").isEmpty();

            cache.putText(LayerRoute.FORECAST, v1, FORECAST_QUERIES.get(2), "{}", Duration.ofMillis(300));
            assertThat(cache.findText(LayerRoute.FORECAST, v1, FORECAST_QUERIES.get(2))).contains("{}");
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (cache.findText(LayerRoute.FORECAST, v1, FORECAST_QUERIES.get(2)).isPresent() && System.nanoTime() < deadline) {
                Thread.sleep(50);
            }
            assertThat(cache.findText(LayerRoute.FORECAST, v1, FORECAST_QUERIES.get(2))).as("expired after its TTL").isEmpty();
            assertThat(cache.findText(LayerRoute.FORECAST, v1, q)).as("the 48 h entry is still there").contains(body);
        } finally {
            cache.close();
        }
    }

    @Test
    void aFailedComputationStoresNothing() {
        RedisLayerCache cache = cache("err");
        try {
            assertThatThrownBy(() -> cache.get(LayerRoute.FWI, A, "aoi_id=NOPE", Answer.class, () -> {
                throw new IllegalArgumentException("unknown aoi");
            })).isInstanceOf(IllegalArgumentException.class);
            assertThat(cache.get(LayerRoute.FWI, A, "aoi_id=NOPE", Answer.class, () -> new Answer("computed", List.of(), 0)).aoiId())
                    .isEqualTo("computed");
        } finally {
            cache.close();
        }
    }

    @Test
    void aDeadRedisIsAMissNotAnError() {
        RedisLayerCache cache = new RedisLayerCache(new LayerCacheSettings(true, "redis://127.0.0.1:" + freePort(), "dead",
                Duration.ofMillis(200), Duration.ofMillis(200), Duration.ofSeconds(30)));
        try {
            assertThat(cache.get(LayerRoute.FWI, A, "aoi_id=KR", Answer.class, () -> new Answer("KR", List.of(), 1)).aoiId()).isEqualTo("KR");
            assertThat(cache.json(LayerRoute.OCCURRENCE_GRID, A, "q", () -> "{}")).isEqualTo("{}");
            cache.putText(LayerRoute.FORECAST, FORECAST_VERSIONS.get(0), "q", "{}", Duration.ofHours(48));
            assertThat(cache.findText(LayerRoute.FORECAST, FORECAST_VERSIONS.get(0), "q")).isEmpty();
        } finally {
            cache.close();
        }
    }

    RedisLayerCache cache(String env) {
        return new RedisLayerCache(new LayerCacheSettings(true, "redis://127.0.0.1:" + port, env, null, null, null));
    }

    static int freePort() {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static RedisServer start(int port) {
        try {
            RedisServer server = RedisServer.newRedisServer().port(port).bind("127.0.0.1").setting("save \"\"")
                    .setting("appendonly no").build();
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

package io.emberline.acceptance.limits;

import static org.assertj.core.api.Assertions.assertThat;

import io.emberline.EmberlineApplication;
import io.emberline.support.AppTestBase;
import io.emberline.support.Duck;
import io.emberline.support.EmbeddedPg;
import io.emberline.support.Fixtures;
import io.emberline.support.ReleaseFixtures;
import io.lettuce.core.Range;
import io.lettuce.core.StreamMessage;
import io.lettuce.core.api.sync.RedisCommands;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** release.activated through the publication registry → Redis Streams → inbox / DLQ. */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EventsTest extends AppTestBase {

    static final Redises REDIS = new Redises().start();
    static final String STREAM = "em:test:evt:release";
    static final String DLQ = "em:test:dlq:release";
    Path root;
    RedisCommands<String, String> durable;
    long warmCount;

    @DynamicPropertySource
    static void redis(DynamicPropertyRegistry r) {
        r.add("spring.data.redis.url", REDIS::cacheUrl);
        r.add("emberline.events.durable-url", REDIS::durableUrl);
        r.add("emberline.events.env", () -> "test");
        r.add("emberline.events.reclaim-interval", () -> "1s");
        r.add("emberline.events.min-idle", () -> "1s");
        r.add("emberline.release.poll-interval", () -> "30s");
    }

    @BeforeAll
    void setUp() {
        root = ReleaseFixtures.newRoot();
        ReleaseFixtures.copyCore(root, "core-b");
        ReleaseFixtures.copyCore(root, "core-c");
        durable = Redises.client(REDIS.durableUrl());
        warmCount = ((Number) Duck.query("SELECT count(*) FROM read_parquet(?) WHERE level IN ('country', 'kr', 'sido', 'sgg')",
                Duck.core("aoi_list.parquet")).get(0).get(0)).longValue();
    }

    @AfterAll
    void stopRedis() {
        REDIS.stop();
    }

    List<StreamMessage<String, String>> entries() {
        return durable.xrange(STREAM, Range.create("-", "+"));
    }

    static void await(String what, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("timed out waiting for " + what);
    }

    double warmTotal() {
        for (String line : getManagement("/actuator/prometheus").body().split("\n")) {
            if (line.startsWith("emberline_cache_warm_total")) {
                return Double.parseDouble(line.substring(line.lastIndexOf(' ') + 1));
            }
        }
        return -1;
    }

    List<List<String>> sql(String query) throws Exception {
        List<List<String>> rows = new ArrayList<>();
        try (Connection c = EmbeddedPg.dataSource(jdbcUrl).getConnection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(query)) {
            while (rs.next()) {
                List<String> row = new ArrayList<>();
                for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
                    row.add(rs.getString(i));
                }
                rows.add(row);
            }
        }
        return rows;
    }

    String relayPublication(String releaseKey, boolean completed) throws Exception {
        List<List<String>> rows = sql("SELECT id::text FROM platform.event_publication WHERE listener_id LIKE '%RedisStreamsExternalizer%' "
                + "AND serialized_event LIKE '%\"" + releaseKey + "\"%' AND completion_date IS " + (completed ? "NOT " : "") + "NULL");
        assertThat(rows).hasSize(1);
        return rows.get(0).get(0);
    }

    @Test
    @Order(1)
    void b4_06_activationAddsExactlyOneEnvelope() throws Exception {
        assertThat(warmTotal()).isZero();
        assertThat(Cli.run(jdbcUrl, root, REDIS.durableUrl(), "stage", "core")).isZero();
        assertThat(Cli.run(jdbcUrl, root, REDIS.durableUrl(), "activate", "core", "--expect-sha256", Fixtures.coreManifestSha256())).isZero();
        List<StreamMessage<String, String>> all = entries();
        assertThat(all).hasSize(1);
        Map<String, String> f = all.get(0).getBody();
        assertThat(f.get("eventId")).isEqualTo(relayPublication("core", true));
        assertThat(f.get("type")).isEqualTo("release.activated");
        assertThat(f.get("schemaVersion")).isEqualTo("1");
        assertThat(Instant.parse(f.get("occurredAt"))).isBefore(Instant.now());
        assertThat(f.get("aggregateType")).isEqualTo("release");
        assertThat(f.get("aggregateId")).isEqualTo("core");
        assertThat(f.get("producer")).startsWith("emberline@");
        assertThat(f.get("traceparent")).matches("^00-[0-9a-f]{32}-[0-9a-f]{16}-01$");
        assertThat(f.get("payload")).contains("\"release_key\":\"core\"");
        // l1-refresh (broadcast) switches this pod long before the 30 s poll; cache-warmer warms once
        await("l1-refresh", () -> get("/api/v1/version").body().contains("\"release_key\":\"core\""));
        await("cache-warmer", () -> warmTotal() == warmCount);
    }

    @Test
    @Order(2)
    void b4_07_failedActivationAddsNothing() {
        assertThat(Cli.run(jdbcUrl, root, REDIS.durableUrl(), "stage", "core-b")).isZero();
        assertThat(Cli.run(jdbcUrl, root, REDIS.durableUrl(), "activate", "core-b", "--expect-sha256", "0".repeat(64))).isEqualTo(4);
        assertThat(entries()).hasSize(1);
    }

    @Test
    @Order(3)
    void b4_09_sameEventIdTwiceWarmsOnce() throws Exception {
        Map<String, String> original = entries().get(0).getBody();
        durable.xadd(STREAM, original);
        durable.xadd(STREAM, original);
        await("deliveries acknowledged", () -> durable.xpending(STREAM, "cache-warmer").getCount() == 0
                && lastDelivered().equals(entries().get(entries().size() - 1).getId()));
        Thread.sleep(500);
        assertThat(warmTotal()).isEqualTo(warmCount);
        assertThat(sql("SELECT count(*) FROM platform.processed_event WHERE consumer = 'cache-warmer'").get(0).get(0)).isEqualTo("1");
    }

    String lastDelivered() {
        for (Object o : durable.xinfoGroups(STREAM)) {
            List<?> g = (List<?>) o;
            Map<String, Object> m = new LinkedHashMap<>();
            for (int i = 0; i + 1 < g.size(); i += 2) {
                m.put(String.valueOf(g.get(i)), g.get(i + 1));
            }
            if ("cache-warmer".equals(m.get("name"))) {
                return String.valueOf(m.get("last-delivered-id"));
            }
        }
        return "";
    }

    @Test
    @Order(4)
    void b4_10_unknownEventIdGoesToDlqAndChangesNothing() throws Exception {
        String before = get("/api/v1/aois/KR").headers().firstValue("X-Method-Version").orElseThrow();
        Map<String, String> forged = new LinkedHashMap<>(entries().get(0).getBody());
        forged.put("eventId", UUID.randomUUID().toString());
        forged.put("payload", forged.get("payload").replace("\"method_version\":\"v1-m2v-2026-11-14\"", "\"method_version\":\"forged\""));
        long dlq = durable.xlen(DLQ);
        durable.xadd(STREAM, forged);
        await("dlq", () -> durable.xlen(DLQ) == dlq + 1);
        Map<String, String> dead = durable.xrange(DLQ, Range.create("-", "+")).get((int) dlq).getBody();
        assertThat(dead.get("eventId")).isEqualTo(forged.get("eventId"));
        assertThat(dead.get("dlqReason")).contains("not a stored publication");
        Thread.sleep(1000);
        assertThat(get("/api/v1/aois/KR").headers().firstValue("X-Method-Version")).hasValue(before);
        assertThat(warmTotal()).isEqualTo(warmCount);
    }

    @Test
    @Order(5)
    void b4_11_undecodablePayloadGoesToDlqAtOnceAndLeavesNothingPending() throws Exception {
        Map<String, String> broken = new LinkedHashMap<>(entries().get(0).getBody());
        broken.put("payload", "{not json");
        long dlq = durable.xlen(DLQ);
        durable.xadd(STREAM, broken);
        await("dlq", () -> durable.xlen(DLQ) == dlq + 1);
        assertThat(durable.xrange(DLQ, Range.create("-", "+")).get((int) dlq).getBody().get("dlqReason")).contains("deserialized");
        assertThat(durable.xpending(STREAM, "cache-warmer").getCount()).isZero();
        String gauge = getManagement("/actuator/prometheus").body();
        assertThat(gauge).contains("emberline_dlq_size{stream=\"em:test:dlq:release\"}");
        assertThat(gauge).contains("emberline_events_lag{group=\"cache-warmer\",stream=\"em:test:evt:release\"}");
    }

    @Test
    @Order(6)
    void b4_08_activationWhileRedisDurableIsDownIsPublishedOnceAfterRestart() throws Exception {
        assertThat(Cli.run(jdbcUrl, root, REDIS.durableUrl(), "stage", "core-c")).isZero();
        REDIS.stopDurable();
        String sha = Fixtures.sha256(root.resolve("core-c/SHA256SUMS"));
        try {
            assertThat(Cli.run(jdbcUrl, root, REDIS.durableUrl(), "activate", "core-c", "--expect-sha256", sha)).isZero();
        } finally {
            REDIS.startDurable();
        }
        durable = Redises.client(REDIS.durableUrl());
        String stored = relayPublication("core-c", false);
        assertThat(entries()).isEmpty();
        SpringApplication app = new SpringApplication(EmberlineApplication.class);
        try (ConfigurableApplicationContext restarted = app.run("--spring.datasource.url=" + jdbcUrl,
                "--spring.datasource.username=postgres", "--server.port=0", "--management.server.port=0",
                "--spring.data.redis.url=" + REDIS.cacheUrl(), "--emberline.events.durable-url=" + REDIS.durableUrl(),
                "--emberline.events.env=test", "--emberline.release.root=" + root, "--emberline.ratelimit.enabled=false",
                "--spring.main.banner-mode=off")) {
            await("republished entry", () -> !entries().isEmpty());
            Thread.sleep(1000);
            List<StreamMessage<String, String>> all = entries();
            assertThat(all).hasSize(1);
            assertThat(all.get(0).getBody().get("eventId")).isEqualTo(stored);
            assertThat(all.get(0).getBody().get("aggregateId")).isEqualTo("core-c");
            assertThat(relayPublication("core-c", true)).isEqualTo(stored);
        }
    }
}

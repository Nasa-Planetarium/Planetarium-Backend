package io.emberline.outlook;

import static org.assertj.core.api.Assertions.assertThat;

import io.emberline.support.AppTestBase;
import io.emberline.support.ForecastStub;
import io.emberline.support.ForecastStub.Answer;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import redis.embedded.RedisServer;
import tools.jackson.databind.JsonNode;

/**
 * over real HTTP: {@code POST /api/v1/forecast} against a local stub of the forecast service
 * ({@link ForecastStub}, the interface of MAPPING §9.3) and an embedded redis-cache. One row per validation rule
 * (the stub must see no call), byte-for-byte pass-through on a miss and on a hit, the service error mapping, the cache
 * key rules, and the hand-off alias. The KST day switch is {@code ForecastServiceTest} (fixed clock).
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
// own stub and Redis are stopped in @AfterAll: close the context too, so its 16 pooled connections do not stay
// open in the context cache for the rest of the suite (embedded Postgres max_connections 300)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ForecastApiTest extends AppTestBase {

    static final ForecastStub STUB = new ForecastStub();
    static final int REDIS_PORT = freePort();
    static final RedisServer REDIS = startRedis();
    static final String AS_OF = "2026-11-14";
    static final String DATA_VERSION = "planner-2026-11-14#abc";
    /** Korean text, unsorted keys, 1.0 / 3825013 / 1e-07 / -0.0 / 0.6190, indentation: none of it may change. */
    static final String MODEL_BODY = "{\n \"product\": \"FireOutlook Planner\",\n \"region\": \"aoi_id SIDO_51\",\n"
            + " \"as_of\": \"2026-11-14\",\n \"p_any\": 0.6190, \"expected_count\": 1.0, \"expected_count_q90\": 3825013,\n"
            + " \"tiny\": 1e-07, \"zero\": -0.0, \"daily\": [{\"date\": \"2026-11-15\", \"p_day\": 0.0631, \"source\": \"daily model\"}],\n"
            + " \"display_hint\": \"\", \"notice\": \"위성 탐지 기반 확률이며 산불 발생을 보장하지 않습니다.\"\n}";

    @DynamicPropertySource
    static void forecast(DynamicPropertyRegistry r) {
        r.add("emberline.forecast.url", STUB::url);
        r.add("emberline.forecast.timeout", () -> "1s");
        r.add("emberline.forecast.status-ttl", () -> "200ms");
        r.add("emberline.layer-cache.url", () -> "redis://127.0.0.1:" + REDIS_PORT);
        r.add("emberline.layer-cache.env", () -> "test");
    }

    @BeforeEach
    void reset() {
        STUB.answer = Answer.json(200, MODEL_BODY);
        STUB.health = Answer.json(200, ForecastStub.health(AS_OF, DATA_VERSION));
        STUB.bodies.clear();
    }

    @AfterAll
    void stop() {
        STUB.stop();
        try {
            REDIS.stop();
        } catch (IOException | RuntimeException e) {
            // already stopped by aRedisFailureIsAMiss
        }
    }

    // ---- validation: one row per rule, the service is never called ----

    static Stream<Arguments> invalid() {
        String ring = "[[126.5,37.0],[127.5,37.0],[127.5,38.0],[126.5,37.0]]";
        return Stream.of(
                // body
                Arguments.of("not JSON", "{\"region\": \"SIDO_51\", ", 400, 10001, null),
                Arguments.of("not an object", "[\"SIDO_51\", 30]", 400, 10001, null),
                Arguments.of("duplicate member", "{\"region\":\"SIDO_51\",\"region\":\"SIDO_11\",\"horizon_days\":30}", 400, 10001, null),
                Arguments.of("trailing tokens", "{\"region\":\"SIDO_51\",\"horizon_days\":30} {}", 400, 10001, null),
                Arguments.of("unknown member as_of", "{\"region\":\"SIDO_51\",\"horizon_days\":30,\"as_of\":\"2026-11-10\"}", 400, 10001, "as_of"),
                Arguments.of("unknown member today", "{\"region\":\"SIDO_51\",\"horizon_days\":30,\"today\":\"2026-11-20\"}", 400, 10001, "today"),
                // region
                Arguments.of("region missing", "{\"horizon_days\":30}", 400, 40005, "region"),
                Arguments.of("region null", "{\"region\":null,\"horizon_days\":30}", 400, 40005, "region"),
                Arguments.of("region a number", "{\"region\":51,\"horizon_days\":30}", 400, 40005, "region"),
                Arguments.of("aoi_id format", "{\"region\":\"SIDO 51' OR 1=1\",\"horizon_days\":30}", 400, 40005, "region"),
                Arguments.of("aoi_id too long", "{\"region\":\"" + "A".repeat(65) + "\",\"horizon_days\":30}", 400, 40005, "region"),
                Arguments.of("bbox 3 numbers", "{\"region\":[126,37,127],\"horizon_days\":30}", 400, 40005, "region"),
                Arguments.of("bbox not numbers", "{\"region\":[\"126\",37,127,38],\"horizon_days\":30}", 400, 40005, "region"),
                Arguments.of("bbox west >= east", "{\"region\":[127.5,37,126.5,38],\"horizon_days\":30}", 400, 40005, "region"),
                Arguments.of("bbox south >= north", "{\"region\":[126.5,38,127.5,38],\"horizon_days\":30}", 400, 40005, "region"),
                Arguments.of("bbox longitude range", "{\"region\":[-181,37,127.5,38],\"horizon_days\":30}", 400, 40005, "region"),
                Arguments.of("bbox latitude range", "{\"region\":[126.5,-91,127.5,38],\"horizon_days\":30}", 400, 40005, "region"),
                Arguments.of("bbox infinite", "{\"region\":[126.5,37,1e400,38],\"horizon_days\":30}", 400, 40005, "region"),
                Arguments.of("GeoJSON Feature", "{\"region\":{\"type\":\"Feature\",\"geometry\":{\"type\":\"Polygon\",\"coordinates\":[" + ring + "]}},\"horizon_days\":30}", 400, 40005, "region"),
                Arguments.of("GeoJSON Point", "{\"region\":{\"type\":\"Point\",\"coordinates\":[126.5,37]},\"horizon_days\":30}", 400, 40005, "region"),
                Arguments.of("polygon ring not closed", "{\"region\":{\"type\":\"Polygon\",\"coordinates\":[[[126.5,37.0],[127.5,37.0],[127.5,38.0],[126.5,37.5]]]},\"horizon_days\":30}", 400, 40001, "region"),
                Arguments.of("polygon ring under 4 positions", "{\"region\":{\"type\":\"Polygon\",\"coordinates\":[[[126.5,37.0],[127.5,37.0],[126.5,37.0]]]},\"horizon_days\":30}", 400, 40001, "region"),
                Arguments.of("polygon self-intersecting", "{\"region\":{\"type\":\"Polygon\",\"coordinates\":[[[126,37],[127,38],[127,37],[126,38],[126,37]]]},\"horizon_days\":30}", 400, 40001, "region"),
                Arguments.of("multipolygon overlapping", "{\"region\":{\"type\":\"MultiPolygon\",\"coordinates\":[[" + ring + "],[" + ring + "]]},\"horizon_days\":30}", 400, 40001, "region"),
                Arguments.of("polygon 3D position", "{\"region\":{\"type\":\"Polygon\",\"coordinates\":[[[126.5,37.0,1],[127.5,37.0,1],[127.5,38.0,1],[126.5,37.0,1]]]},\"horizon_days\":30}", 400, 40001, "region"),
                Arguments.of("polygon latitude range", "{\"region\":{\"type\":\"Polygon\",\"coordinates\":[[[126.5,37.0],[127.5,37.0],[127.5,95.0],[126.5,37.0]]]},\"horizon_days\":30}", 400, 40001, "region"),
                Arguments.of("polygon coordinates not arrays", "{\"region\":{\"type\":\"Polygon\",\"coordinates\":\"126,37\"},\"horizon_days\":30}", 400, 40001, "region"),
                Arguments.of("polygon vertex cap", "{\"region\":" + bigPolygon(2001) + ",\"horizon_days\":30}", 400, 40108, "region"),
                // period
                Arguments.of("no period", "{\"region\":\"SIDO_51\"}", 400, 40006, "start"),
                Arguments.of("start without end", "{\"region\":\"SIDO_51\",\"start\":\"2026-12-01\"}", 400, 40006, "end"),
                Arguments.of("end without start", "{\"region\":\"SIDO_51\",\"end\":\"2026-12-31\"}", 400, 40006, "start"),
                Arguments.of("horizon_days with end", "{\"region\":\"SIDO_51\",\"horizon_days\":30,\"end\":\"2026-12-31\"}", 400, 40006, "end"),
                Arguments.of("horizon_days 0", "{\"region\":\"SIDO_51\",\"horizon_days\":0}", 400, 40006, "horizon_days"),
                Arguments.of("horizon_days 31", "{\"region\":\"SIDO_51\",\"horizon_days\":31}", 400, 40006, "horizon_days"),
                Arguments.of("horizon_days -1", "{\"region\":\"SIDO_51\",\"horizon_days\":-1}", 400, 40006, "horizon_days"),
                Arguments.of("horizon_days 30.0", "{\"region\":\"SIDO_51\",\"horizon_days\":30.0}", 400, 40006, "horizon_days"),
                Arguments.of("horizon_days \"30\"", "{\"region\":\"SIDO_51\",\"horizon_days\":\"30\"}", 400, 40006, "horizon_days"),
                Arguments.of("horizon_days true", "{\"region\":\"SIDO_51\",\"horizon_days\":true}", 400, 40006, "horizon_days"),
                Arguments.of("horizon_days huge", "{\"region\":\"SIDO_51\",\"horizon_days\":123456789012345678901234567890}", 400, 40006, "horizon_days"),
                Arguments.of("start not a date (horizon)", "{\"region\":\"SIDO_51\",\"horizon_days\":30,\"start\":\"tomorrow\"}", 400, 40006, "start"),
                Arguments.of("impossible date", "{\"region\":\"SIDO_51\",\"start\":\"2026-02-30\",\"end\":\"2026-03-31\"}", 400, 40006, "start"),
                Arguments.of("date without zero padding", "{\"region\":\"SIDO_51\",\"start\":\"2026-12-1\",\"end\":\"2026-12-31\"}", 400, 40006, "start"),
                Arguments.of("date a number", "{\"region\":\"SIDO_51\",\"start\":\"2026-12-01\",\"end\":20261231}", 400, 40006, "end"),
                Arguments.of("end before start", "{\"region\":\"SIDO_51\",\"start\":\"2026-12-31\",\"end\":\"2026-12-01\"}", 400, 40006, "end"),
                Arguments.of("span over 400 days", "{\"region\":\"SIDO_51\",\"start\":\"2026-11-15\",\"end\":\"2027-12-20\"}", 400, 40109, "end"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalid")
    @Order(1)
    void eachValidationRuleAnswersItsProblemWithoutCallingTheService(String row, String body, int status, int errorNo, String param) {
        HttpResponse<byte[]> r = post("/api/v1/forecast", body);
        assertThat(r.statusCode()).as(row).isEqualTo(status);
        JsonNode p = problem(r);
        assertThat(p.get("error_no").asInt()).as(row).isEqualTo(errorNo);
        assertThat(p.get("param") == null || p.get("param").isNull() ? null : p.get("param").asString()).as(row).isEqualTo(param);
        assertThat(r.headers().firstValue("Cache-Control")).as(row).hasValue("no-store");
        assertThat(STUB.calls()).as(row).isZero();
    }

    @Test
    @Order(2)
    void bodyOverTheCapIs413WithAndWithoutContentLength() {
        String big = "{\"region\":\"SIDO_51\",\"horizon_days\":30,\"pad\":\"" + "x".repeat(131072) + "\"}";
        HttpResponse<byte[]> sized = post("/api/v1/forecast", big);
        assertThat(sized.statusCode()).isEqualTo(413);
        assertThat(problem(sized).get("error_no").asInt()).isEqualTo(40110);
        assertThat(problem(sized).get("code").asString()).isEqualTo("range_too_large");
        // chunked: no Content-Length, counted while reading
        HttpResponse<byte[]> chunked = send(HttpRequest.newBuilder(uri("/api/v1/forecast")).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(big.getBytes(StandardCharsets.UTF_8)))));
        assertThat(chunked.statusCode()).isEqualTo(413);
        assertThat(problem(chunked).get("error_no").asInt()).isEqualTo(40110);
        // exactly at the cap is read (and then refused for its unknown member, not for size)
        String atCap = "{\"region\":\"SIDO_51\",\"horizon_days\":30,\"pad\":\"\"}";
        atCap = atCap.replace("\"\"}", "\"" + "x".repeat(131072 - atCap.length()) + "\"}");
        assertThat(atCap.getBytes(StandardCharsets.UTF_8)).hasSize(131072);
        HttpResponse<byte[]> edge = post("/api/v1/forecast", atCap);
        assertThat(edge.statusCode()).isEqualTo(400);
        assertThat(problem(edge).get("param").asString()).isEqualTo("pad");
        assertThat(STUB.calls()).isZero();
    }

    @Test
    @Order(3)
    void wrongContentTypeAndGetAreRefused() {
        HttpResponse<byte[]> text = send(HttpRequest.newBuilder(uri("/api/v1/forecast")).header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString("{\"region\":\"SIDO_51\",\"horizon_days\":30}")));
        assertThat(text.statusCode()).isEqualTo(400);
        assertThat(problem(text).get("error_no").asInt()).isEqualTo(10001);
        HttpResponse<byte[]> get = send(HttpRequest.newBuilder(uri("/api/v1/forecast")).GET());
        assertThat(get.statusCode()).isEqualTo(405);
        assertThat(get.headers().firstValue("Allow")).hasValue("POST");
        assertThat(STUB.calls()).isZero();
    }

    @Test
    @Order(4)
    void largestAllowedPolygonIsForwarded() {
        HttpResponse<byte[]> r = post("/api/v1/forecast", "{\"region\":" + bigPolygon(2000) + ",\"start\":\"2027-01-01\",\"end\":\"2027-03-31\"}");
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(STUB.calls()).isOne();
    }

    // ---- pass-through ----

    @Test
    @Order(10)
    void answerIsTheServiceBytesOnAMissAndOnAHit() {
        String body = "{\"region\": \"SIDO_51\", \"horizon_days\": 30}";
        HttpResponse<byte[]> miss = post("/api/v1/forecast", body);
        assertThat(miss.statusCode()).isEqualTo(200);
        assertThat(miss.body()).isEqualTo(MODEL_BODY.getBytes(StandardCharsets.UTF_8));
        assertThat(miss.headers().firstValue("Content-Type")).hasValueSatisfying(t -> assertThat(t).startsWith("application/json"));
        assertThat(miss.headers().firstValue("Cache-Control")).hasValue("no-store");
        assertThat(miss.headers().firstValue("ETag")).isEmpty();
        assertThat(STUB.bodies).containsExactly("{\"horizon_days\":30,\"region\":\"SIDO_51\"}");

        // the same query in another member order and spacing: a hit, the same bytes, no second call
        HttpResponse<byte[]> hit = post("/api/v1/forecast", "{\"horizon_days\":30,\"region\":\"SIDO_51\"}");
        assertThat(hit.statusCode()).isEqualTo(200);
        assertThat(hit.body()).isEqualTo(miss.body());
        assertThat(hit.headers().firstValue("Cache-Control")).hasValue("no-store");
        assertThat(STUB.calls()).isOne();

        // the stored entry: key = data_version@as_of + KST date (horizon_days) + sha256, value verbatim, with a TTL
        String today = LocalDate.now(ZoneId.of("Asia/Seoul")).toString();
        List<String> keys = redis().keys("em:test:c:layer:forecast:*");
        assertThat(keys).anyMatch(k -> k.startsWith("em:test:c:layer:forecast:s1:" + DATA_VERSION + "@" + AS_OF + ":kst" + today + ":"));
        String key = keys.stream().filter(k -> k.contains(":kst" + today + ":")).findFirst().orElseThrow();
        assertThat(redis().get(key)).isEqualTo(MODEL_BODY);
        assertThat(redis().ttl(key)).isBetween(1L, Duration.ofHours(48).toSeconds());
    }

    @Test
    @Order(11)
    void forwardedBodyIsTheCanonicalQuery() {
        post("/api/v1/forecast", "{\"region\": [126.5, 37, 127.50, 3.8e1], \"end\": \"2027-03-15\", \"start\": \"2027-03-01\"}");
        post("/api/v1/forecast", "{\"start\": \"2026-11-20\", \"horizon_days\": 30, \"region\": \"ADM1_ARG-1299\"}");
        post("/api/v1/forecast", "{\"region\": {\"coordinates\": [[[126.5, 37], [127.5, -0.0], [127.5, 38], [126.5, 37]]], \"type\": \"Polygon\"},"
                + " \"start\": \"2027-01-01\", \"end\": \"2027-03-31\", \"horizon_days\": null}");
        assertThat(STUB.bodies).containsExactly(
                "{\"end\":\"2027-03-15\",\"region\":[126.5,37.0,127.5,38.0],\"start\":\"2027-03-01\"}",
                "{\"horizon_days\":30,\"region\":\"ADM1_ARG-1299\",\"start\":\"2026-11-20\"}",
                "{\"end\":\"2027-03-31\",\"region\":{\"coordinates\":[[[126.5,37.0],[127.5,0.0],[127.5,38.0],[126.5,37.0]]],\"type\":\"Polygon\"},\"start\":\"2027-01-01\"}");
        // bbox written two ways is one query → a hit
        post("/api/v1/forecast", "{\"region\": [126.50, 37.0, 127.5, 38], \"start\": \"2027-03-01\", \"end\": \"2027-03-15\"}");
        assertThat(STUB.calls()).isEqualTo(3);
    }

    @Test
    @Order(12)
    void handoffAliasForwardsTheBodyToTheSameAnswer() {
        STUB.answer = Answer.json(200, MODEL_BODY);
        HttpResponse<byte[]> alias = post("/api/forecast", "{\"region\":\"SGG_47730\",\"horizon_days\":30}");
        assertThat(alias.statusCode()).isEqualTo(200);
        assertThat(alias.body()).isEqualTo(MODEL_BODY.getBytes(StandardCharsets.UTF_8));
        assertThat(alias.headers().firstValue("Link")).hasValue("</api/v1/forecast>; rel=\"canonical\"");
        assertThat(alias.headers().firstValue("Cache-Control")).hasValue("no-store");
        HttpResponse<byte[]> canonical = post("/api/v1/forecast", "{\"region\":\"SGG_47730\",\"horizon_days\":30}");
        assertThat(canonical.body()).isEqualTo(alias.body());
        assertThat(STUB.calls()).isOne();
        // the alias validates the same way
        assertThat(problem(post("/api/forecast", "{\"region\":\"SGG_47730\",\"horizon_days\":31}")).get("error_no").asInt()).isEqualTo(40006);
        assertThat(post("/api/forecast", "{\"region\":\"SGG_47730\",\"pad\":\"" + "x".repeat(131072) + "\"}").statusCode()).isEqualTo(413);
    }

    // ---- service errors ----

    @Test
    @Order(20)
    void service404WithItsJsonErrorIsUnknownAoi60401() {
        STUB.answer = Answer.json(404, "{\"error\": \"unknown_aoi\", \"message\": \"모르는 aoi_id: SGG_99999\"}");
        HttpResponse<byte[]> r = post("/api/v1/forecast", "{\"region\":\"SGG_99999\",\"horizon_days\":30}");
        assertThat(r.statusCode()).isEqualTo(404);
        JsonNode p = problem(r);
        assertThat(p.get("code").asString()).isEqualTo("unknown_aoi");
        assertThat(p.get("error_no").asInt()).isEqualTo(60401);
        assertThat(p.get("param").asString()).isEqualTo("region");
        assertThat(p.get("detail").asString()).isEqualTo("모르는 aoi_id: SGG_99999");
        assertThat(r.headers().firstValue("Content-Type")).hasValueSatisfying(t -> assertThat(t).startsWith("application/problem+json"));
        assertThat(r.headers().firstValue("Cache-Control")).hasValue("no-store");
    }

    @Test
    @Order(21)
    void service400WithItsJsonErrorIsInvalidParam60001AndIsNeverCached() {
        String message = "기간은 기준일(2026-11-14) 다음 날부터, 시작 ≤ 끝이어야 합니다";
        STUB.answer = Answer.json(400, "{\"error\": \"invalid_period\", \"message\": \"" + message + "\"}");
        String body = "{\"region\":\"SIDO_51\",\"start\":\"2026-11-10\",\"end\":\"2026-11-20\"}";
        HttpResponse<byte[]> r = post("/api/v1/forecast", body);
        assertThat(r.statusCode()).isEqualTo(400);
        JsonNode p = problem(r);
        assertThat(p.get("code").asString()).isEqualTo("invalid_param");
        assertThat(p.get("error_no").asInt()).isEqualTo(60001);
        assertThat(p.get("detail").asString()).isEqualTo(message);
        assertThat(post("/api/v1/forecast", body).statusCode()).isEqualTo(400);
        assertThat(STUB.calls()).isEqualTo(2);
    }

    @Test
    @Order(22)
    void unsafeServiceMessagesAreNotEchoed() {
        STUB.answer = Answer.json(400, "{\"error\": \"x\", \"message\": \"Traceback: /opt/app/planetarium/forecast.py line 463\"}");
        JsonNode p = problem(post("/api/v1/forecast", "{\"region\":\"SIDO_11\",\"start\":\"2028-01-01\",\"end\":\"2028-01-31\"}"));
        assertThat(p.get("error_no").asInt()).isEqualTo(60001);
        assertThat(p.get("detail").asString()).isEqualTo("the forecast service cannot answer this query");
        STUB.answer = Answer.json(404, "{\"error\": \"unknown_aoi\"}");
        p = problem(post("/api/v1/forecast", "{\"region\":\"SGG_00000\",\"horizon_days\":30}"));
        assertThat(p.get("error_no").asInt()).isEqualTo(60401);
        assertThat(p.get("detail").asString()).isEqualTo("the forecast service does not know this region");
    }

    static Stream<Arguments> unavailable() {
        return Stream.of(
                Arguments.of("5xx", Answer.json(500, "{\"error\": \"internal\", \"message\": \"boom\"}")),
                Arguments.of("502 html", Answer.raw(502, "text/html", "<html>bad gateway</html>".getBytes(StandardCharsets.UTF_8))),
                Arguments.of("404 not the service's JSON error", Answer.raw(404, "text/html", "<h1>Not Found</h1>".getBytes(StandardCharsets.UTF_8))),
                Arguments.of("400 without error member", Answer.json(400, "{\"detail\": \"validation\"}")),
                Arguments.of("422 (another 4xx)", Answer.json(422, "{\"error\": \"unprocessable\", \"message\": \"x\"}")),
                Arguments.of("200 not JSON", Answer.raw(200, "text/plain", "ok".getBytes(StandardCharsets.UTF_8))),
                Arguments.of("200 JSON array", Answer.json(200, "[1, 2]")),
                Arguments.of("timeout", Answer.slow(2500, MODEL_BODY)),
                Arguments.of("connection closed without an answer", Answer.dropped()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unavailable")
    @Order(23)
    void transportFailuresAre503ForecastUnavailable(String row, Answer answer) {
        STUB.answer = answer;
        HttpResponse<byte[]> r = post("/api/v1/forecast", "{\"region\":\"SIDO_26\",\"start\":\"2027-02-01\",\"end\":\"2027-02-28\"}");
        assertThat(r.statusCode()).as(row).isEqualTo(503);
        JsonNode p = problem(r);
        assertThat(p.get("code").asString()).as(row).isEqualTo("upstream_unavailable");
        assertThat(p.get("error_no").asInt()).as(row).isEqualTo(60503);
        assertThat(r.headers().firstValue("Retry-After")).as(row).hasValue("30");
        assertThat(new String(r.body(), StandardCharsets.UTF_8)).as(row).doesNotContain("boom").doesNotContain("<html>");
    }

    // ---- cache keys ----

    @Test
    @Order(30)
    void anAsOfChangeOnHealthzSwitchesTheKey() throws InterruptedException {
        String body = "{\"region\":\"SIDO_47\",\"start\":\"2026-12-01\",\"end\":\"2026-12-31\"}";
        assertThat(post("/api/v1/forecast", body).statusCode()).isEqualTo(200);
        assertThat(post("/api/v1/forecast", body).statusCode()).isEqualTo(200);
        assertThat(STUB.calls()).isOne();
        // the daily refresh: the service now answers from as_of 2026-11-15
        String next = MODEL_BODY.replace("\"as_of\": \"2026-11-14\"", "\"as_of\": \"2026-11-15\"");
        STUB.health = Answer.json(200, ForecastStub.health("2026-11-15", "planner-2026-11-15#def"));
        STUB.answer = Answer.json(200, next);
        Thread.sleep(400);  // > emberline.forecast.status-ttl
        HttpResponse<byte[]> fresh = post("/api/v1/forecast", body);
        assertThat(fresh.body()).isEqualTo(next.getBytes(StandardCharsets.UTF_8));
        assertThat(STUB.calls()).isEqualTo(2);
        assertThat(post("/api/v1/forecast", body).body()).isEqualTo(next.getBytes(StandardCharsets.UTF_8));
        assertThat(STUB.calls()).isEqualTo(2);
        assertThat(redis().keys("em:test:c:layer:forecast:s1:planner-2026-11-15#def@2026-11-15:*")).hasSize(1);
        STUB.health = Answer.json(200, ForecastStub.health(AS_OF, DATA_VERSION));
        Thread.sleep(400);
    }

    @Test
    @Order(31)
    void anAnswerFromAnotherAsOfThanHealthzIsNotStored() {
        // the service swapped its data between /healthz and the answer
        STUB.answer = Answer.json(200, MODEL_BODY.replace("\"as_of\": \"2026-11-14\"", "\"as_of\": \"2026-11-15\""));
        String body = "{\"region\":\"SIDO_48\",\"start\":\"2026-12-01\",\"end\":\"2026-12-31\"}";
        assertThat(post("/api/v1/forecast", body).statusCode()).isEqualTo(200);
        assertThat(post("/api/v1/forecast", body).statusCode()).isEqualTo(200);
        assertThat(STUB.calls()).isEqualTo(2);
    }

    @Test
    @Order(32)
    void rangeQueriesCarryNoDateInTheKey() {
        post("/api/v1/forecast", "{\"region\":\"SIDO_49\",\"start\":\"2026-12-01\",\"end\":\"2026-12-31\"}");
        List<String> keys = redis().keys("em:test:c:layer:forecast:s1:" + DATA_VERSION + "@" + AS_OF + ":*");
        assertThat(keys).anyMatch(k -> k.matches(".*@2026-11-14:[0-9a-f]{64}$"));
    }

    @Test
    @Order(33)
    void withoutHealthzTheQueryIsForwardedAndNothingIsStored() throws InterruptedException {
        STUB.health = Answer.json(503, "{\"warm\": false}");
        Thread.sleep(400);
        String body = "{\"region\":\"SIDO_50\",\"start\":\"2026-12-01\",\"end\":\"2026-12-31\"}";
        assertThat(post("/api/v1/forecast", body).body()).isEqualTo(MODEL_BODY.getBytes(StandardCharsets.UTF_8));
        assertThat(post("/api/v1/forecast", body).body()).isEqualTo(MODEL_BODY.getBytes(StandardCharsets.UTF_8));
        assertThat(STUB.calls()).isEqualTo(2);
        STUB.health = Answer.json(200, ForecastStub.health(AS_OF, DATA_VERSION));
        Thread.sleep(400);
    }

    @Test
    @Order(34)
    void healthzIsNotAskedPerRequest() throws InterruptedException {
        Thread.sleep(400);
        int before = STUB.healthCalls.get();
        for (int i = 0; i < 10; i++) {
            post("/api/v1/forecast", "{\"region\":\"SIDO_41\",\"start\":\"2026-12-01\",\"end\":\"2026-12-31\"}");
        }
        // reused for emberline.forecast.status-ttl (200 ms here): far fewer than one per request
        assertThat(STUB.healthCalls.get() - before).isBetween(1, 4);
    }

    @Test
    @Order(99)
    void aRedisFailureIsAMiss() throws IOException {
        REDIS.stop();
        String body = "{\"region\":\"SIDO_43\",\"start\":\"2026-12-01\",\"end\":\"2026-12-31\"}";
        HttpResponse<byte[]> r = post("/api/v1/forecast", body);
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.body()).isEqualTo(MODEL_BODY.getBytes(StandardCharsets.UTF_8));
        assertThat(post("/api/v1/forecast", body).statusCode()).isEqualTo(200);
        assertThat(STUB.calls()).isEqualTo(2);
    }

    // ---- helpers ----

    /** A valid polygon (a circle) with {@code n} positions in its only ring. */
    static String bigPolygon(int n) {
        StringBuilder b = new StringBuilder("{\"type\":\"Polygon\",\"coordinates\":[[");
        for (int i = 0; i < n - 1; i++) {
            double a = 2 * Math.PI * i / (n - 1);
            b.append(i == 0 ? "" : ",").append('[').append(127 + Math.cos(a)).append(',').append(37 + Math.sin(a)).append(']');
        }
        return b.append(",[").append(128.0).append(',').append(37.0).append("]]]}").toString();
    }

    URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    HttpResponse<byte[]> post(String path, String body) {
        return send(HttpRequest.newBuilder(uri(path)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)));
    }

    HttpResponse<byte[]> send(HttpRequest.Builder b) {
        try {
            return HTTP.send(b.timeout(Duration.ofSeconds(30)).build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    static JsonNode problem(HttpResponse<byte[]> r) {
        return JSON.readTree(r.body());
    }

    private StatefulRedisConnection<String, String> connection;

    io.lettuce.core.api.sync.RedisCommands<String, String> redis() {
        if (connection == null) {
            connection = RedisClient.create("redis://127.0.0.1:" + REDIS_PORT).connect();
        }
        return connection.sync();
    }

    static int freePort() {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static RedisServer startRedis() {
        try {
            RedisServer s = RedisServer.newRedisServer().port(REDIS_PORT).bind("127.0.0.1").setting("save \"\"")
                    .setting("appendonly no").setting("maxmemory-policy allkeys-lru").build();
            s.start();
            return s;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

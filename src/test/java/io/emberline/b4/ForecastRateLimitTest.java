package io.emberline.b4;

import static org.assertj.core.api.Assertions.assertThat;

import io.emberline.support.AppTestBase;
import io.emberline.support.ForecastStub;
import io.emberline.support.ForecastStub.Answer;
import io.lettuce.core.api.sync.RedisCommands;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * item 5: {@code POST /api/v1/forecast} has its own scope {@code forecast} (default 10 per minute, stricter than
 * heavy's 30), shared with the alias {@code /api/forecast}; over it → 429 rate_limited (10301) naming the scope, with
 * Retry-After, before the body is read and before the forecast service is called. Other routes keep their buckets.
 * Scope ip is raised so the forecast bucket is what is measured; forecast keeps its default.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
// own stub and Redis are stopped in @AfterAll: close the context too, so its 16 pooled connections do not stay
// open in the context cache for the rest of the suite (embedded Postgres max_connections 300)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ForecastRateLimitTest extends AppTestBase {

    static final Redises REDIS = new Redises().start();
    static final ForecastStub STUB = new ForecastStub();
    static final String BODY = "{\"region\":\"SIDO_51\",\"horizon_days\":30}";

    RedisCommands<String, String> durable;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.data.redis.url", REDIS::cacheUrl);
        r.add("emberline.layer-cache.url", REDIS::cacheUrl);
        r.add("emberline.events.durable-url", REDIS::durableUrl);
        r.add("emberline.forecast.url", STUB::url);
        r.add("emberline.ratelimit.enabled", () -> "true");
        r.add("emberline.ratelimit.ip.capacity", () -> "100000");
        r.add("emberline.ratelimit.ip.refill-tokens", () -> "100000");
    }

    @BeforeAll
    void clients() {
        durable = Redises.client(REDIS.durableUrl());
    }

    @BeforeEach
    void freshBuckets() {
        durable.keys("em:test:rl:*").forEach(durable::del);
        STUB.answer = Answer.json(200, "{\"as_of\": \"2026-11-14\", \"p_any\": 0.619}");
        STUB.bodies.clear();
    }

    @AfterAll
    void stop() {
        STUB.stop();
        REDIS.stop();
    }

    @Test
    @Order(1)
    void theEleventhForecastInAMinuteIs429NamingScopeForecast() {
        for (int i = 1; i <= 10; i++) {
            // canonical and alias paths spend the same bucket
            HttpResponse<String> r = post(i % 2 == 0 ? "/api/forecast" : "/api/v1/forecast", BODY);
            assertThat(r.statusCode()).as("forecast %d: %s", i, r.body()).isEqualTo(200);
        }
        int calls = STUB.calls();
        HttpResponse<String> limited = post("/api/v1/forecast", BODY);
        assertThat(limited.statusCode()).isEqualTo(429);
        assertThat(limited.headers().firstValue("Content-Type").orElse("")).startsWith("application/problem+json");
        assertThat(limited.headers().firstValue("Retry-After").orElse("")).matches("^[1-9][0-9]*$");
        assertThat(limited.headers().firstValue("Cache-Control")).hasValue("no-store");
        JsonNode body = json(limited);
        assertThat(body.get("code").asString()).isEqualTo("rate_limited");
        assertThat(body.get("error_no").asInt()).isEqualTo(10301);
        assertThat(body.get("detail").asString()).contains("rate limit scope forecast");
        assertThat(post("/api/forecast", BODY).statusCode()).isEqualTo(429);
        // refused before the body is read: an oversized body is 429, not 413, and the service saw nothing more
        assertThat(post("/api/v1/forecast", "{\"pad\":\"" + "x".repeat(200_000) + "\"}").statusCode()).isEqualTo(429);
        assertThat(STUB.calls()).isEqualTo(calls);
        // the GET routes are not in scope forecast
        assertThat(get("/api/v1/version").statusCode()).isEqualTo(200);
    }

    @Test
    @Order(2)
    void invalidBodiesStillSpendTheBucket() {
        for (int i = 1; i <= 10; i++) {
            assertThat(post("/api/v1/forecast", "{\"region\":\"SIDO_51\",\"horizon_days\":99}").statusCode()).as("bad %d", i).isEqualTo(400);
        }
        assertThat(post("/api/v1/forecast", BODY).statusCode()).isEqualTo(429);
        assertThat(STUB.calls()).isZero();
    }

    HttpResponse<String> post(String path, String body) {
        return send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)));
    }
}

package io.emberline.b0;

import static org.assertj.core.api.Assertions.assertThat;

import io.emberline.support.AppTestBase;
import io.emberline.support.Contract;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** B0.01–B0.10 over real HTTP (public port + management port), no release active. */
class B0SkeletonTest extends AppTestBase {

    @Test
    void b0_01_unknownRouteIsProblem404() {
        HttpResponse<String> r = get("/api/v1/does-not-exist");
        assertThat(r.statusCode()).isEqualTo(404);
        assertThat(r.headers().firstValue("Content-Type")).hasValueSatisfying(v -> assertThat(v).startsWith("application/problem+json"));
        JsonNode body = json(r);
        assertThat(body.get("status").asInt()).isEqualTo(404);
        assertThat(body.get("code").asString()).isEqualTo("not_found");
        assertThat(body.get("error_no").asInt()).isEqualTo(10401);
        assertThat(body.get("type").asString()).isEqualTo("https://emberline.example/errors/not_found");
        assertThat(body.get("instance").asString()).isEqualTo("/api/v1/does-not-exist");
        for (String f : new String[] {"title", "detail", "request_id"}) {
            assertThat(body.hasNonNull(f)).as(f).isTrue();
        }
    }

    @Test
    void b0_02_requestIdEchoedOrReplaced() {
        HttpResponse<String> plain = get("/api/v1/does-not-exist");
        assertThat(plain.headers().firstValue("X-Request-Id")).hasValue(json(plain).get("request_id").asString());

        HttpResponse<String> echoed = get("/api/v1/does-not-exist", "X-Request-Id", "abc-12345-XYZ");
        assertThat(echoed.headers().firstValue("X-Request-Id")).hasValue("abc-12345-XYZ");
        assertThat(json(echoed).get("request_id").asString()).isEqualTo("abc-12345-XYZ");

        String tooLong = "a".repeat(65);
        for (String bad : new String[] {"has spaces in it", tooLong, "short"}) {
            HttpResponse<String> r = get("/api/v1/does-not-exist", "X-Request-Id", bad);
            String id = r.headers().firstValue("X-Request-Id").orElseThrow();
            assertThat(id).isNotEqualTo(bad).matches("^[A-Za-z0-9-]{8,64}$");
            assertThat(json(r).get("request_id").asString()).isEqualTo(id);
        }
    }

    @Test
    void b0_03_postVersionIs405WithAllow() {
        HttpResponse<String> r = send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/version"))
                .POST(HttpRequest.BodyPublishers.noBody()));
        assertThat(r.statusCode()).isEqualTo(405);
        assertThat(r.headers().firstValue("Content-Type").orElse("")).startsWith("application/problem+json");
        assertThat(json(r).get("code").asString()).isEqualTo("method_not_allowed");
        assertThat(json(r).get("error_no").asInt()).isEqualTo(10002);
        assertThat(r.headers().allValues("Allow").toString()).contains("GET");
    }

    @Test
    void b0_04_unsupportedAcceptIs406() {
        HttpResponse<String> r = get("/api/v1/version", "Accept", "text/xml");
        assertThat(r.statusCode()).isEqualTo(406);
        assertThat(r.headers().firstValue("Content-Type").orElse("")).startsWith("application/problem+json");
        assertThat(json(r).get("code").asString()).isEqualTo("not_acceptable");
        assertThat(json(r).get("error_no").asInt()).isEqualTo(10003);
    }

    @Test
    void b0_05_versionHasSnakeCaseKeysAndContractLimits() {
        HttpResponse<String> r = get("/api/v1/version");
        assertThat(r.statusCode()).isEqualTo(200);
        JsonNode body = json(r);
        assertThat(body.get("api_version").asString()).isEqualTo("v1");
        assertThat(body.has("release")).isTrue();
        assertThat(body.get("release").isNull()).isTrue();
        Map<String, Integer> limits = new TreeMap<>();
        body.get("limits").properties().forEach(e -> limits.put(e.getKey(), e.getValue().asInt()));
        assertThat(limits).isEqualTo(Contract.limits());
    }

    @Test
    void b0_06_titleFollowsAcceptLanguageAndDetailIsSafe() {
        JsonNode ko = json(get("/api/v1/does-not-exist", "Accept-Language", "ko"));
        JsonNode en = json(get("/api/v1/does-not-exist", "Accept-Language", "en"));
        JsonNode koKr = json(get("/api/v1/does-not-exist", "Accept-Language", "ko-KR,ko;q=0.9,en;q=0.5"));
        assertThat(ko.get("title").asString()).isEqualTo("찾을 수 없음");
        assertThat(koKr.get("title").asString()).isEqualTo("찾을 수 없음");
        assertThat(en.get("title").asString()).isEqualTo("Not found");
        for (JsonNode p : new JsonNode[] {ko, en, json(get("/api/v1/version", "Accept", "text/xml")),
                json(send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/version"))
                        .POST(HttpRequest.BodyPublishers.noBody())))}) {
            String detail = p.get("detail").asString();
            assertThat(detail).doesNotContainPattern("(^|[\\s\"'(])/([\\w.-]+/)+[\\w.-]+|[A-Za-z]:\\\\|\\.(parquet|geojson|sql|class)\\b") // file paths
                    .doesNotContainPattern("\\b(java|jakarta|org|io)\\.[a-z]")    // class names
                    .doesNotContainPattern("\\tat |Exception|\\.java:")            // stack frames
                    .doesNotContainPattern("(?i)\\b(select|insert|update|delete)\\b.*\\b(from|into|set)\\b");
        }
    }

    @Test
    void b0_07_problemNoStoreAndNoCookie() {
        assertThat(get("/api/v1/does-not-exist").headers().firstValue("Cache-Control")).hasValue("no-store");
        HttpResponse<String> version = get("/api/v1/version");
        assertThat(version.statusCode()).isEqualTo(200);
        assertThat(version.headers().allValues("Set-Cookie")).isEmpty();
    }

    @Test
    void b0_08_healthOnlyOnManagementPort() {
        HttpResponse<String> health = getManagement("/actuator/health");
        assertThat(health.statusCode()).isEqualTo(200);
        assertThat(json(health).get("status").asString()).isEqualTo("UP");
        HttpResponse<String> publicHealth = get("/actuator/health");
        assertThat(publicHealth.statusCode()).isEqualTo(404);
        assertThat(publicHealth.headers().firstValue("Content-Type").orElse("")).startsWith("application/problem+json");
    }

    @Test
    void b0_09_managementExposesOnlyHealthInfoPrometheus() {
        get("/api/v1/version");
        assertThat(getManagement("/actuator/env").statusCode()).isEqualTo(404);
        assertThat(getManagement("/actuator/beans").statusCode()).isEqualTo(404);
        HttpResponse<String> prom = getManagement("/actuator/prometheus");
        assertThat(prom.statusCode()).isEqualTo(200);
        assertThat(prom.body()).contains("http_server_requests");
    }

    @Test
    void b0_10_corsOnlyForConfiguredOrigins() {
        HttpResponse<String> evil = send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/version"))
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody()), "Origin", "https://evil.example",
                "Access-Control-Request-Method", "GET");
        assertThat(evil.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
        HttpResponse<String> good = send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/version"))
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody()), "Origin", "https://app.example",
                "Access-Control-Request-Method", "GET");
        assertThat(good.headers().firstValue("Access-Control-Allow-Origin")).hasValue("https://app.example");
    }
}

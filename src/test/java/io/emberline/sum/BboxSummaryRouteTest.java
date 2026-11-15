package io.emberline.sum;

import static org.assertj.core.api.Assertions.assertThat;

import io.emberline.support.AppTestBase;
import io.emberline.support.Fixtures;
import io.emberline.support.IngestCli;
import io.emberline.support.ReleaseFixtures;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * pl-sum: GET/HEAD /api/v1/summaries (openapi getBboxSummary, {@code x-status: release-absent}) checks its parameters
 * (400) and then answers 503 data_not_loaded (50501) even with an active release; the hand-off alias /api/summary with
 * {@code bbox} forwards to it. At fdf1ea8 no route served /summaries (404 not_found) and /api/summary?bbox= forwarded
 * to /api/v1/aois//summary (400 with Spring's default JSON body).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
// own context (and database): a cached context of another class may already hold a release with the same key
@TestPropertySource(properties = {"emberline.release.poll-interval=1s", "pl-sum.test-context=bbox-summary"})
class BboxSummaryRouteTest extends AppTestBase {

    static final String QUERY = "bbox=126,34,127,35&start=2025-03-01&end=2025-03-31&freq=month";

    @BeforeAll
    void activate() throws InterruptedException {
        Path root = ReleaseFixtures.newRoot();
        ReleaseFixtures.copyCore(root, "core-b");
        assertThat(IngestCli.run(jdbcUrl, root, "stage", "core").exit()).isZero();
        assertThat(IngestCli.run(jdbcUrl, root, "activate", "core", "--expect-sha256", Fixtures.coreManifestSha256()).exit()).isZero();
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            JsonNode release = json(get("/api/v1/version")).get("release");
            if (release != null && !release.isNull() && "core".equals(release.get("release_key").asString())) {
                return;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("release core not active");
    }

    /** At fdf1ea8: 404 not_found ("no route for this path") — no controller mapped /api/v1/summaries. */
    @Test
    void validParametersGive503DataNotLoadedNoStore() {
        HttpResponse<String> r = get("/api/v1/summaries?" + QUERY);
        assertThat(r.statusCode()).isEqualTo(503);
        assertThat(r.headers().firstValue("Content-Type").orElse("")).startsWith("application/problem+json");
        assertThat(r.headers().firstValue("Cache-Control")).hasValue("no-store");
        assertThat(r.headers().firstValue("Retry-After")).hasValue("60");
        assertThat(r.headers().firstValue("ETag")).isEmpty();
        JsonNode body = json(r);
        assertThat(body.get("status").asInt()).isEqualTo(503);
        assertThat(body.get("code").asString()).isEqualTo("data_not_loaded");
        assertThat(body.get("error_no").asInt()).isEqualTo(50501);
        assertThat(body.get("type").asString()).endsWith("/data_not_loaded");
        assertThat(body.get("instance").asString()).isEqualTo("/api/v1/summaries");
        assertThat(body.get("method_version").asString()).isNotEmpty();
        // freq=day and format=csv are valid too: still 503 problem+json
        assertThat(get("/api/v1/summaries?bbox=126,34,127,35&start=2025-03-01&end=2025-03-31&freq=day").statusCode()).isEqualTo(503);
        HttpResponse<String> csv = get("/api/v1/summaries?" + QUERY + "&format=csv");
        assertThat(csv.statusCode()).isEqualTo(503);
        assertThat(csv.headers().firstValue("Content-Type").orElse("")).startsWith("application/problem+json");
    }

    /** At fdf1ea8: 404 — HEAD maps to the same (absent) GET route. */
    @Test
    void headGivesTheSameStatusWithAnEmptyBody() {
        HttpResponse<String> r = send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/summaries?" + QUERY))
                .method("HEAD", HttpRequest.BodyPublishers.noBody()));
        assertThat(r.statusCode()).isEqualTo(503);
        assertThat(r.body()).isEmpty();
        assertThat(r.headers().firstValue("Cache-Control")).hasValue("no-store");
    }

    /** At fdf1ea8: every case is 404 not_found (no route), not 400 with the param. */
    @Test
    void invalidParametersGive400BeforeThe503() {
        Map<String, String> cases = Map.ofEntries(
                Map.entry("start=2025-03-01&end=2025-03-31", "bbox"),
                Map.entry("bbox=abc&start=2025-03-01&end=2025-03-31", "bbox"),
                Map.entry("bbox=126,34,127&start=2025-03-01&end=2025-03-31", "bbox"),
                Map.entry("bbox=127,34,126,35&start=2025-03-01&end=2025-03-31", "bbox"),
                Map.entry("bbox=126,-91,127,35&start=2025-03-01&end=2025-03-31", "bbox"),
                Map.entry("bbox=126,34,127,35&end=2025-03-31", "start"),
                Map.entry("bbox=126,34,127,35&start=2025-03-01", "end"),
                Map.entry("bbox=126,34,127,35&start=2025-02-30&end=2025-03-31", "start"),
                Map.entry("bbox=126,34,127,35&start=2025-04-01&end=2025-03-31", "start"),
                Map.entry("bbox=126,34,127,35&start=2025-03-01&end=2025-03-31&freq=week", "freq"));
        cases.forEach((query, param) -> {
            HttpResponse<String> r = get("/api/v1/summaries?" + query);
            assertThat(r.statusCode()).as(query).isEqualTo(400);
            assertThat(r.headers().firstValue("Content-Type").orElse("")).as(query).startsWith("application/problem+json");
            assertThat(r.headers().firstValue("Cache-Control")).as(query).hasValue("no-store");
            JsonNode body = json(r);
            assertThat(body.get("code").asString()).as(query).isEqualTo("invalid_param");
            assertThat(body.get("param").asString()).as(query).isEqualTo(param);
        });
        HttpResponse<String> tooLong = get("/api/v1/summaries?bbox=126,34,127,35&start=2024-01-01&end=2025-03-31&freq=day");
        assertThat(tooLong.statusCode()).isEqualTo(400);
        assertThat(json(tooLong).get("code").asString()).isEqualTo("range_too_large");
        assertThat(json(tooLong).get("error_no").asInt()).isEqualTo(30101);
    }

    /** At fdf1ea8: /api/summary?bbox= forwarded to /api/v1/aois//summary → 400 with Spring's default JSON (not a problem). */
    @Test
    void aliasGivesTheSameStatusAndProblemShape() {
        HttpResponse<String> alias = get("/api/summary?" + QUERY);
        HttpResponse<String> canonical = get("/api/v1/summaries?" + QUERY);
        assertThat(alias.statusCode()).isEqualTo(503);
        assertThat(alias.headers().firstValue("Content-Type").orElse("")).startsWith("application/problem+json");
        assertThat(alias.headers().firstValue("Cache-Control")).hasValue("no-store");
        assertThat(alias.headers().firstValue("Link")).hasValue("</api/v1/summaries?" + QUERY + ">; rel=\"canonical\"");
        assertThat(withoutRequestScoped(json(alias))).isEqualTo(withoutRequestScoped(json(canonical)));
        assertThat(json(alias).get("instance").asString()).isEqualTo("/api/summary");

        HttpResponse<String> bad = get("/api/summary?bbox=127,34,126,35&start=2025-03-01&end=2025-03-31");
        assertThat(bad.statusCode()).isEqualTo(400);
        assertThat(bad.headers().firstValue("Content-Type").orElse("")).startsWith("application/problem+json");
        assertThat(json(bad).get("param").asString()).isEqualTo("bbox");

        // aoi_id keeps the AOI summary
        HttpResponse<String> aoi = get("/api/summary?aoi_id=KR&start=2025-01-01&end=2025-03-31&freq=month");
        assertThat(aoi.statusCode()).isEqualTo(200);
        assertThat(json(aoi)).isEqualTo(json(get("/api/v1/aois/KR/summary?start=2025-01-01&end=2025-03-31&freq=month")));
    }

    private static JsonNode withoutRequestScoped(JsonNode problem) {
        ObjectNode copy = (ObjectNode) problem.deepCopy();
        copy.remove("instance");
        copy.remove("request_id");
        return copy;
    }
}

package io.emberline.pub;

import static org.assertj.core.api.Assertions.assertThat;

import io.emberline.support.AppTestBase;
import io.emberline.support.Fixtures;
import io.emberline.support.IngestCli;
import io.emberline.support.ReleaseFixtures;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.test.context.TestPropertySource;

/**
 * pl-pub item 3 (Spring side): release-pinned GETs carry {@code public, max-age=60, s-maxage=600} and an ETag from the
 * active data_version + the request; If-None-Match → 304; another data_version → another ETag; /version has its own
 * short policy; errors carry no public cache header.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
// own context (and database): a cached context of another class may already hold a different core-b
@TestPropertySource(properties = {"emberline.release.poll-interval=1s", "pl-pub.test-context=cache-headers"})
class PublicCacheHeadersTest extends AppTestBase {

    static final String DATA = "public, max-age=60, s-maxage=600";
    static final List<String> PINNED = List.of("/api/v1/aois?level=country", "/api/v1/aois/KR", "/api/v1/aois/KR/boundary",
            "/api/v1/aois/KR/calendar?start_year=2024&end_year=2025", "/api/v1/aois/KR/months/2025-03/comparison",
            "/api/v1/aois/KR/summary?start=2025-01-01&end=2025-03-31&freq=month", "/api/v1/cells?aoi_id=KR&month=2025-03",
            "/api/v1/methods/current");
    // /api/v1/satellite-status left PINNED — it is @LayerCached now
    // (public, max-age=0, s-maxage=3600, must-revalidate) and answers 503 on core, which ships no satellite files;
    // its layer headers, ETag and 304 are pinned by io.emberline.acceptance.s5layers.NewLayerApiTest (L3-7) on the v1.2 files.

    Path root;
    String coreEtag;

    @BeforeAll
    void activate() throws InterruptedException {
        root = ReleaseFixtures.newRoot();
        ReleaseFixtures.copyCore(root, "core-b");
        assertThat(IngestCli.run(jdbcUrl, root, "stage", "core").exit()).isZero();
        assertThat(IngestCli.run(jdbcUrl, root, "activate", "core", "--expect-sha256", Fixtures.coreManifestSha256()).exit()).isZero();
        waitForRelease("core");
    }

    void waitForRelease(String key) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            var release = json(get("/api/v1/version")).get("release");
            if (release != null && !release.isNull() && key.equals(release.get("release_key").asString())) {
                return;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("release " + key + " not active");
    }

    @Test
    @Order(1)
    void releasePinnedGetsCarryPublicMaxAgeSMaxAgeAndAnEtag() {
        for (String p : PINNED) {
            HttpResponse<String> r = get(p);
            assertThat(r.statusCode()).as(p).isEqualTo(200);
            assertThat(r.headers().firstValue("Cache-Control")).as(p).hasValue(DATA);
            assertThat(r.headers().firstValue("ETag").orElse("")).as(p).matches("^\"[0-9a-f]{32}\"$");
            assertThat(r.headers().firstValue("X-Data-Version").orElse("")).as(p).startsWith("core#");
        }
        HttpResponse<String> version = get("/api/v1/version");
        assertThat(version.headers().firstValue("Cache-Control")).hasValue("public, max-age=0, s-maxage=5, must-revalidate");
        assertThat(version.headers().firstValue("ETag")).isPresent();
    }

    @Test
    @Order(2)
    void ifNoneMatchGives304WithTheSameCacheHeaders() {
        HttpResponse<String> first = get("/api/v1/aois/KR/summary?start=2025-01-01&end=2025-03-31&freq=month");
        coreEtag = first.headers().firstValue("ETag").orElseThrow();
        HttpResponse<String> again = get("/api/v1/aois/KR/summary?freq=month&end=2025-03-31&start=2025-01-01", "If-None-Match", coreEtag);
        assertThat(again.statusCode()).isEqualTo(304);
        assertThat(again.body()).isEmpty();
        assertThat(again.headers().firstValue("ETag")).hasValue(coreEtag);
        assertThat(again.headers().firstValue("Cache-Control")).hasValue(DATA);
        // the request is part of the ETag
        assertThat(get("/api/v1/aois/KR/summary?start=2025-01-01&end=2025-03-30&freq=month").headers().firstValue("ETag"))
                .isNotEqualTo(java.util.Optional.of(coreEtag));
    }

    @Test
    @Order(3)
    void errorsCarryNoPublicCacheHeader() {
        for (String p : List.of("/api/v1/aois/NOPE", "/api/v1/does-not-exist", "/api/v1/aois/KR/summary?start=2025-13-01",
                "/api/v1/cells?aoi_id=KR&month=2025-03&format=xml")) {
            HttpResponse<String> r = get(p);
            assertThat(r.statusCode()).as(p).isBetween(400, 599);
            String cc = r.headers().firstValue("Cache-Control").orElse("");
            assertThat(cc).as(p).isEqualTo("no-store").doesNotContain("public");
            assertThat(r.headers().firstValue("ETag")).as(p).isEmpty();
        }
    }

    @Test
    @Order(4)
    void anotherDataVersionGivesAnotherEtag() throws InterruptedException {
        String sha = Fixtures.sha256(root.resolve("core-b/SHA256SUMS"));
        assertThat(IngestCli.run(jdbcUrl, root, "stage", "core-b").exit()).isZero();
        assertThat(IngestCli.run(jdbcUrl, root, "activate", "core-b", "--expect-sha256", sha).exit()).isZero();
        waitForRelease("core-b");
        HttpResponse<String> r = get("/api/v1/aois/KR/summary?start=2025-01-01&end=2025-03-31&freq=month", "If-None-Match", coreEtag);
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.headers().firstValue("X-Data-Version").orElse("")).startsWith("core-b#");
        assertThat(r.headers().firstValue("ETag").orElseThrow()).isNotEqualTo(coreEtag);
        assertThat(r.headers().firstValue("Cache-Control")).hasValue(DATA);
    }
}

package io.emberline.b2;

import static org.assertj.core.api.Assertions.assertThat;

import io.emberline.support.IngestCli;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.test.context.TestPropertySource;

/** B2.12 with emberline.limits.csv-rows=10 (as the harness sets it); Redis unreachable on purpose (DB answers). */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestPropertySource(properties = {"emberline.limits.csv-rows=10", "emberline.release.poll-interval=1s",
        "emberline.harmonized.cache.url=redis://127.0.0.1:1"})
class B2CsvLimitTest extends B2AppBase {

    @BeforeAll
    void activate() throws Exception {
        assertThat(IngestCli.run(jdbcUrl, ROOT, "stage", "v11").exit()).isZero();
        assertThat(IngestCli.run(jdbcUrl, ROOT, "activate", "v11", "--expect-sha256", V11Fixtures.manifestSha(ROOT, "v11")).exit()).isZero();
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline && json(get("/api/v1/version")).get("release").isNull()) {
            Thread.sleep(200);
        }
    }

    @Test
    void b2_12_csvOverTheRowLimitIs30102() {
        HttpResponse<String> csv = get("/api/v1/aois/KR/calendar?start_year=2025&end_year=2025", "Accept", "text/csv");
        assertThat(csv.statusCode()).isEqualTo(400);
        assertThat(csv.headers().firstValue("Content-Type").orElse("")).startsWith("application/problem+json");
        assertThat(json(csv).get("code").asString()).isEqualTo("range_too_large");
        assertThat(json(csv).get("error_no").asInt()).isEqualTo(30102);
        assertThat(json(csv).get("detail").asString()).contains("narrow");
        assertThat(csv.headers().firstValue("ETag")).isEmpty();
        assertThat(get("/api/v1/aois/KR/calendar?start_year=2025&end_year=2025&format=csv").statusCode()).isEqualTo(400);
        assertThat(get("/api/export.csv?type=calendar&aoi_id=KR&start_year=2025&end_year=2025").statusCode()).isEqualTo(400);
        assertThat(get("/api/v1/aois/KR/calendar?start_year=2025&end_year=2025").statusCode()).as("JSON is not capped").isEqualTo(200);
        HttpResponse<String> few = get("/api/v1/aois/KR/summary?start=2025-01-01&end=2025-10-31&format=csv");
        assertThat(few.statusCode()).as("10 rows are allowed").isEqualTo(200);
    }
}

package io.emberline.acceptance.read;

import static io.emberline.acceptance.read.ReadSupport.num;
import static io.emberline.acceptance.read.ReadSupport.text;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import io.emberline.support.Duck;
import io.emberline.support.Fixtures;
import io.emberline.support.IngestCli;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * over real HTTP: owner fixture core-v11 (release v1.1, real values) staged as {@code v11} with one change — the
 * name of SIDO_11 is {@code =1+2} ( formula guard) — then the v1 fixture {@code core} for the switch.
 * Expected numbers come from the release files (DuckDB), never from our code.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestPropertySource(properties = {"emberline.release.poll-interval=1s", "emberline.harmonized.cache.retry-after=200ms",
        "emberline.harmonized.cache.env=test"})
class ReadApiTest extends ReadAppBase {

    static final String MV = "v1-m2v-2026-11-14";
    static final EmbeddedRedis REDIS = new EmbeddedRedis().start();
    ReadSupport.Keys keys;

    @DynamicPropertySource
    static void b2(DynamicPropertyRegistry registry) {
        registry.add("emberline.harmonized.cache.url", REDIS::url);
    }

    @BeforeAll
    void activateV11() throws Exception {
        keys = new ReadSupport.Keys(REDIS.url());
        IngestCli.Result staged = IngestCli.run(jdbcUrl, ROOT, "stage", "v11");
        assertThat(staged.exit()).as(staged.json().toString()).isZero();
        IngestCli.Result active = IngestCli.run(jdbcUrl, ROOT, "activate", "v11", "--expect-sha256", V11Fixtures.manifestSha(ROOT, "v11"));
        assertThat(active.exit()).as(active.json().toString()).isZero();
        assertThat(waitForRelease("v11")).isTrue();
    }

    @AfterAll
    void stopRedis() {
        keys.close();
        REDIS.stop();
    }

    boolean waitForRelease(String key) throws InterruptedException {
        Predicate<JsonNode> ok = b -> !b.get("release").isNull() && b.get("release").get("release_key").asString().equals(key);
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            if (ok.test(json(get("/api/v1/version")))) {
                return true;
            }
            Thread.sleep(200);
        }
        return false;
    }

    JsonNode ok(String path, String... headers) {
        HttpResponse<String> r = get(path, headers);
        assertThat(r.statusCode()).as(path + " " + r.body()).isEqualTo(200);
        return json(r);
    }

    HttpResponse<byte[]> bytes(String path, String... headers) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET();
        for (int i = 0; i + 1 < headers.length; i += 2) {
            b.header(headers[i], headers[i + 1]);
        }
        try {
            return HTTP.send(b.timeout(Duration.ofSeconds(30)).build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    double metric(String name, String route) {
        String body = getManagement("/actuator/prometheus").body();
        double sum = 0;
        for (String line : body.split("\n")) {
            if (line.startsWith(name + "{") && line.contains("route=\"" + route + "\"")) {
                sum += Double.parseDouble(line.substring(line.lastIndexOf(' ') + 1));
            }
        }
        return sum;
    }

    double metric(String name, String route, String result) {
        String body = getManagement("/actuator/prometheus").body();
        double sum = 0;
        for (String line : body.split("\n")) {
            if (line.startsWith(name + "{") && line.contains("route=\"" + route + "\"") && line.contains("result=\"" + result + "\"")) {
                sum += Double.parseDouble(line.substring(line.lastIndexOf(' ') + 1));
            }
        }
        return sum;
    }

    // ── cache ──

    @Test
    @Order(1)
    void b2_13_oneBundleKeyPerAoiWhateverTheYearRange() {
        keys.flush();
        long before = keys.count(":c:calendar:");
        int n = 0;
        for (int start = 2000; start <= 2026 && n < 100; start++) {
            for (int end = start; end <= Math.min(2026, start + 39) && n < 100; end++, n++) {
                assertThat(get("/api/v1/aois/KR/calendar?start_year=" + start + "&end_year=" + end).statusCode()).isEqualTo(200);
            }
        }
        assertThat(n).isEqualTo(100);
        assertThat(keys.count(":c:calendar:") - before).isEqualTo(1);
        String dv8 = V11Fixtures.manifestSha(ROOT, "v11").substring(0, 8);
        assertThat(keys.keys(":c:calendar:")).containsExactly("em:test:c:calendar:s2:" + MV + ":" + dv8 + ":KR");
    }

    @Test
    @Order(2)
    void b2_16_aCacheMissIsExactlyOneSqlStatement() {
        keys.flush();
        double sqlBefore = metric("emberline_sql_statements_total", "calendar");
        double missBefore = metric("emberline_cache_requests_total", "calendar", "miss");
        assertThat(get("/api/v1/aois/SIDO_47/calendar?start_year=2024&end_year=2025").statusCode()).isEqualTo(200);
        assertThat(metric("emberline_sql_statements_total", "calendar") - sqlBefore).isEqualTo(1.0);
        assertThat(metric("emberline_cache_requests_total", "calendar", "miss") - missBefore).isEqualTo(1.0);
        double hitBefore = metric("emberline_cache_requests_total", "calendar", "hit");
        assertThat(get("/api/v1/aois/SIDO_47/calendar?start_year=2001&end_year=2003").statusCode()).isEqualTo(200);
        assertThat(metric("emberline_sql_statements_total", "calendar") - sqlBefore).as("a hit sends no SQL").isEqualTo(1.0);
        assertThat(metric("emberline_cache_requests_total", "calendar", "hit") - hitBefore).isEqualTo(1.0);
    }

    // ── calendar ──

    @Test
    @Order(3)
    void b2_01_calendarIsTheStoredRows() {
        JsonNode body = ok("/api/v1/aois/KR/calendar?start_year=2024&end_year=2025");
        assertThat(body.get("aoi_id").asString()).isEqualTo("KR");
        assertThat(body.get("method_version").asString()).isEqualTo(MV);
        assertThat(body.get("unit").asString()).isEqualTo("VIIRS(S-NPP) 기준 화재 감지량");
        assertThat(body.get("start_year").asInt()).isEqualTo(2024);
        assertThat(body.get("end_year").asInt()).isEqualTo(2025);
        assertThat(body.get("event_columns").isEmpty()).isTrue();
        assertThat(body.properties().stream().map(java.util.Map.Entry::getKey).toList())
                .containsExactly("aoi_id", "method_version", "unit", "start_year", "end_year", "event_columns", "months");
        assertCalendarMatches(body, V11, true, "KR", 2024, 2025);
        JsonNode mar = body.get("months").get(12 + 2);
        assertThat(mar.get("value").doubleValue()).isEqualTo(2764.0);
        assertThat(mar.get("percentile").doubleValue()).isEqualTo(100.0);
        assertThat(mar.get("ratio_to_median").doubleValue()).isEqualTo(10.924901185770752);
        assertThat(mar.get("verdict").asString()).isEqualTo("above");
        assertThat(mar.get("raw_modis").asLong()).isEqualTo(772);
        assertThat(mar.get("n_cells").asInt()).isEqualTo(1123);
    }

    static void assertCalendarMatches(JsonNode body, Path dir, boolean v11, String aoi, int from, int to) {
        List<List<Object>> rows = ReadSupport.monthRows(dir, v11, aoi, from, to);
        JsonNode months = body.get("months");
        assertThat(months.size()).isEqualTo((to - from + 1) * 12);
        assertThat(rows).hasSize(months.size());
        for (int i = 0; i < rows.size(); i++) {
            List<Object> e = rows.get(i);
            JsonNode m = months.get(i);
            String at = aoi + " " + e.get(0) + "-" + e.get(1) + " ";
            assertThat(m.get("year").asInt()).isEqualTo(((Number) e.get(0)).intValue());
            assertThat(m.get("month").asInt()).isEqualTo(((Number) e.get(1)).intValue());
            num(m.get("value"), e.get(2), at + "value");
            num(m.get("value_lo"), e.get(3), at + "value_lo");
            num(m.get("value_hi"), e.get(4), at + "value_hi");
            num(m.get("raw_modis"), e.get(5), at + "raw_modis");
            num(m.get("raw_viirs"), e.get(6), at + "raw_viirs");
            text(m.get("value_source"), e.get(7), at + "value_source");
            text(m.get("data_source"), e.get(8), at + "data_source");
            text(m.get("status"), e.get(9), at + "status");
            num(m.get("n_cells"), e.get(10), at + "n_cells");
            if (!"partial".equals(e.get(9))) {
                text(m.get("verdict"), e.get(11), at + "verdict");
            }
            num(m.get("percentile"), e.get(12), at + "percentile");
            num(m.get("ratio_to_median"), e.get(13), at + "ratio_to_median");
            assertThat(m.get("low_sample").isBoolean()).as(at + "low_sample boolean").isTrue();
            assertThat(m.get("low_sample").asBoolean()).isEqualTo(e.get(14));
            num(m.get("value_projected"), e.get(15), at + "value_projected");
            text(m.get("as_of_date"), e.get(16), at + "as_of_date");
        }
    }

    @Test
    @Order(4)
    void b2_02_calendarAliasIsTheCanonicalBodyWithALink() {
        HttpResponse<String> alias = get("/api/calendar?aoi_id=KR&start_year=2024&end_year=2025");
        assertThat(alias.statusCode()).isEqualTo(200);
        assertThat(json(alias)).isEqualTo(ok("/api/v1/aois/KR/calendar?start_year=2024&end_year=2025"));
        assertThat(alias.headers().firstValue("Link"))
                .hasValue("</api/v1/aois/KR/calendar?start_year=2024&end_year=2025>; rel=\"canonical\"");
    }

    // ── comparison ──

    @Test
    @Order(5)
    void b2_03_comparisonIsTheStoredCompareRowAndTheAliasMatches() {
        JsonNode c = ok("/api/v1/aois/KR/months/2025-03/comparison");
        List<Object> e = Duck.query("SELECT value, baseline_median, baseline_p25, baseline_p75, percentile, ratio_to_median, "
                + "n_baseline_years::BIGINT, verdict, low_sample FROM read_parquet(?) WHERE aoi_id = 'KR' AND year = 2025 AND month = 3",
                V11.resolve("aoi_month_compare.parquet")).get(0);
        String[] names = {"value", "baseline_median", "baseline_p25", "baseline_p75", "percentile", "ratio_to_median", "n_baseline_years"};
        for (int i = 0; i < names.length; i++) {
            num(c.get(names[i]), e.get(i), names[i]);
        }
        assertThat(c.get("verdict").asString()).isEqualTo(e.get(7));
        assertThat(c.get("low_sample").asBoolean()).isEqualTo(e.get(8));
        assertThat(c.get("status").asString()).isEqualTo("ok");
        assertThat(c.get("value_source").asString()).isEqualTo("snpp_observed");
        assertThat(c.get("value_lo").doubleValue()).isEqualTo(2764.0);
        assertThat(c.get("method_version").asString()).isEqualTo(MV);
        assertThat(c.get("rule").get("id").asString()).isEqualTo("pct-80-20/n10/ok-only");
        JsonNode baseline = c.get("baseline");
        assertThat(baseline.size()).isEqualTo(24);
        assertThat(baseline.get(0).get("year").asInt()).isEqualTo(2001);
        List<List<Object>> years = Duck.query("SELECT year::INTEGER, value, status FROM read_parquet(?) WHERE aoi_id = 'KR' AND month = 3 "
                + "AND year BETWEEN 2001 AND 2025 AND year <> 2025 ORDER BY year", V11.resolve("aoi_month.parquet"));
        for (int i = 0; i < 24; i++) {
            num(baseline.get(i).get("value"), years.get(i).get(1), "baseline " + years.get(i).get(0));
            assertThat(baseline.get(i).get("used").asBoolean()).isEqualTo("ok".equals(years.get(i).get(2)));
        }
        HttpResponse<String> alias = get("/api/compare?aoi_id=KR&year=2025&month=3");
        assertThat(alias.statusCode()).isEqualTo(200);
        assertThat(json(alias)).isEqualTo(c);
        assertThat(alias.headers().firstValue("Link")).hasValue("</api/v1/aois/KR/months/2025-03/comparison>; rel=\"canonical\"");
        JsonNode sido = ok("/api/v1/aois/SIDO_47/months/2025-03/comparison");
        List<Object> s = Duck.query("SELECT value, baseline_median, percentile, ratio_to_median FROM read_parquet(?) "
                + "WHERE aoi_id = 'SIDO_47' AND year = 2025 AND month = 3", V11.resolve("aoi_month_compare.parquet")).get(0);
        num(sido.get("value"), s.get(0), "SIDO_47 value");
        num(sido.get("baseline_median"), s.get(1), "SIDO_47 baseline_median");
        num(sido.get("percentile"), s.get(2), "SIDO_47 percentile");
        num(sido.get("ratio_to_median"), s.get(3), "SIDO_47 ratio");
        assertThat(get("/api/v1/aois/KR/months/2025-13/comparison").statusCode()).isEqualTo(400);
        JsonNode outside = ok("/api/v1/aois/KR/months/1999-03/comparison");
        assertThat(outside.get("status").asString()).isEqualTo("out_of_range");
        assertThat(outside.get("value").isNull()).isTrue();
    }

    // ── summary ──

    @Test
    @Order(6)
    void b2_04_monthSummaryIsTheAoiMonthRows() {
        JsonNode s = ok("/api/v1/aois/KR/summary?start=2024-01-01&end=2025-12-31&freq=month");
        assertThat(s.get("freq").asString()).isEqualTo("month");
        assertThat(s.get("start").asString()).isEqualTo("2024-01-01");
        List<List<Object>> rows = ReadSupport.monthRows(V11, true, "KR", 2024, 2025);
        JsonNode series = s.get("series");
        assertThat(series.size()).isEqualTo(24);
        double total = 0;
        for (int i = 0; i < 24; i++) {
            JsonNode p = series.get(i);
            List<Object> e = rows.get(i);
            for (String f : List.of("value", "value_lo", "value_hi", "status", "value_source")) {
                assertThat(p.has(f)).as(f).isTrue();
            }
            num(p.get("value"), e.get(2), "value " + i);
            num(p.get("value_lo"), e.get(3), "value_lo " + i);
            num(p.get("value_hi"), e.get(4), "value_hi " + i);
            text(p.get("status"), e.get(9), "status " + i);
            text(p.get("value_source"), e.get(7), "value_source " + i);
            assertThat(p.get("basis").asString()).isEqualTo("aoi_month");
            assertThat(p.get("residual_prorated").asBoolean()).isFalse();
            total += ((Number) e.get(2)).doubleValue();
        }
        assertThat(s.get("total").get("value").doubleValue()).isCloseTo(total, within(1e-9));
        assertThat(s.has("month_residual")).isFalse();
    }

    @Test
    @Order(7)
    void b2_05_storedVerdictAndPercentileWinOverARecomputation() {
        List<List<Object>> disagree = Duck.query("SELECT aoi_id, year::INTEGER, month::INTEGER, verdict, percentile FROM read_parquet(?) "
                + "WHERE status IN ('ok', 'nrt') AND percentile IS NOT NULL AND verdict <> CASE WHEN n_baseline_years < 10 THEN 'uncertain' "
                + "WHEN percentile >= 80 THEN 'above' WHEN percentile <= 20 THEN 'below' ELSE 'similar' END "
                + "AND aoi_id IN ('KR', 'SIDO_47', 'SIDO_11', 'ADM0_JPN', 'CONT_Asia', 'WORLD') ORDER BY aoi_id, year, month LIMIT 25",
                V11.resolve("aoi_month_compare.parquet"));
        assertThat(disagree).as("fixture has rows whose stored verdict differs from the percentile rule").isNotEmpty();
        for (List<Object> row : disagree) {
            String ym = String.format("%04d-%02d", row.get(1), row.get(2));
            JsonNode c = ok("/api/v1/aois/" + row.get(0) + "/months/" + ym + "/comparison");
            assertThat(c.get("verdict").asString()).as(row.toString()).isEqualTo(row.get(3));
            assertThat(c.get("percentile").doubleValue()).as(row.toString()).isEqualTo(((Number) row.get(4)).doubleValue());
        }
        JsonNode partial = ok("/api/v1/aois/KR/months/2026-11/comparison");
        assertThat(partial.get("percentile").doubleValue()).as("stored 8.0 would be below").isEqualTo(8.0);
        assertThat(partial.get("verdict").asString()).isEqualTo("uncertain");
    }

    @Test
    @Order(8)
    void b2_06_lowSampleIsBooleanAndStatusIsAnEnum() {
        Set<String> allowed = Set.of("ok", "nrt", "partial", "missing", "out_of_range");
        Set<String> seen = new TreeSet<>();
        for (String aoi : List.of("KR", "SIDO_11")) {
            JsonNode body = ok("/api/v1/aois/" + aoi + "/calendar?start_year=2000&end_year=2027");
            for (JsonNode m : body.get("months")) {
                seen.add(m.get("status").asString());
                assertThat(m.get("low_sample").isBoolean() || m.get("low_sample").isNull()).isTrue();
                if (!"out_of_range".equals(m.get("status").asString())) {
                    assertThat(m.get("low_sample").isBoolean()).isTrue();
                }
            }
        }
        assertThat(allowed).containsAll(seen);
        assertThat(seen).contains("ok", "nrt", "partial", "missing", "out_of_range");
        JsonNode c = ok("/api/v1/aois/KR/months/2001-06/comparison");
        assertThat(c.get("low_sample").isBoolean()).isTrue();
        assertThat(c.get("status").asString()).isEqualTo("missing");
        assertThat(c.get("value").isNull()).isTrue();
    }

    @Test
    @Order(9)
    void b2_07_yearRangeValidation() {
        HttpResponse<String> order = get("/api/v1/aois/KR/calendar?start_year=2025&end_year=2024");
        assertThat(order.statusCode()).isEqualTo(400);
        assertThat(json(order).get("code").asString()).isEqualTo("invalid_param");
        assertThat(json(order).get("param").asString()).isEqualTo("start_year");
        HttpResponse<String> wide = get("/api/v1/aois/KR/calendar?start_year=2000&end_year=2041");
        assertThat(wide.statusCode()).isEqualTo(400);
        assertThat(json(wide).get("code").asString()).isEqualTo("range_too_large");
        assertThat(json(wide).get("error_no").asInt()).isEqualTo(30101);
        assertThat(json(wide).get("param").asString()).isEqualTo("end_year");
        HttpResponse<String> low = get("/api/v1/aois/KR/calendar?start_year=1999");
        assertThat(low.statusCode()).isEqualTo(400);
        assertThat(json(low).get("param").asString()).isEqualTo("start_year");
        HttpResponse<String> abc = get("/api/v1/aois/KR/calendar?start_year=abc");
        assertThat(abc.statusCode()).isEqualTo(400);
        assertThat(json(abc).get("param").asString()).isEqualTo("start_year");
        assertThat(get("/api/v1/aois/KR/summary?start=2025-03-02&end=2025-03-01").statusCode()).isEqualTo(400);
        assertThat(json(get("/api/v1/aois/KR/summary?start=2025-02-30&end=2025-03-01")).get("param").asString()).isEqualTo("start");
        HttpResponse<String> days = get("/api/v1/aois/KR/summary?start=2024-01-01&end=2025-03-01&freq=day");
        assertThat(days.statusCode()).isEqualTo(400);
        assertThat(json(days).get("error_no").asInt()).isEqualTo(30101);
        assertThat(get("/api/v1/aois/KR/summary?start=2025-01-01").statusCode()).isEqualTo(400);
    }

    // ── CSV ──

    @Test
    @Order(10)
    void b2_08_csvHasBomHeaderAndFormulaGuard() {
        HttpResponse<byte[]> r = bytes("/api/v1/aois/SIDO_11/calendar?start_year=2025&end_year=2025", "Accept", "text/csv");
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.headers().firstValue("Content-Type").orElse("")).startsWith("text/csv");
        byte[] b = r.body();
        assertThat(new byte[] {b[0], b[1], b[2]}).containsExactly(0xEF, 0xBB, 0xBF);
        String[] lines = new String(b, 3, b.length - 3, StandardCharsets.UTF_8).split("\n");
        assertThat(lines[0]).startsWith("aoi_id,year,month,value,value_lo,value_hi,raw_modis,raw_viirs,value_source,data_source,"
                + "status,verdict,percentile,ratio_to_median,n_cells,missing_dates,method_version");
        assertThat(lines).hasSize(13);
        assertThat(lines[3]).startsWith("SIDO_11,2025,3,").endsWith(",'=1+2");
        assertThat(lines[3]).doesNotContain(",=1+2");
        List<Object> mar = Duck.query("SELECT value FROM read_parquet(?) WHERE aoi_id = 'SIDO_11' AND year = 2025 AND month = 3",
                V11.resolve("aoi_month.parquet")).get(0);
        assertThat(lines[3].split(",")[3]).isEqualTo(new java.math.BigDecimal(mar.get(0).toString()).toPlainString());
    }

    @Test
    @Order(11)
    void b2_09_formatCsvIsTheSameBytesAndTheFileNameIsSafe() {
        HttpResponse<byte[]> accept = bytes("/api/v1/aois/KR/calendar?start_year=2024&end_year=2025", "Accept", "text/csv");
        HttpResponse<byte[]> format = bytes("/api/v1/aois/KR/calendar?start_year=2024&end_year=2025&format=csv");
        assertThat(format.statusCode()).isEqualTo(200);
        assertThat(format.body()).isEqualTo(accept.body());
        String disposition = format.headers().firstValue("Content-Disposition").orElse("");
        assertThat(disposition).startsWith("attachment;");
        String name = disposition.replaceAll("^.*filename=\"?([^\";]*)\"?.*$", "$1");
        assertThat(name).isEqualTo("emberline_calendar_KR_2024_2025.csv").matches("^[A-Za-z0-9._-]+$");
        HttpResponse<byte[]> odd = bytes("/api/v1/aois/ADM1_ATA%2B00%3F/calendar?start_year=2024&end_year=2024&format=csv");
        if (odd.statusCode() == 200) {
            String oddName = odd.headers().firstValue("Content-Disposition").orElse("").replaceAll("^.*filename=\"?([^\";]*)\"?.*$", "$1");
            assertThat(oddName).matches("^[A-Za-z0-9._-]+$");
        }
        assertThat(ok("/api/v1/aois/KR/calendar?start_year=2024&end_year=2025&format=json").get("aoi_id").asString()).isEqualTo("KR");
    }

    @Test
    @Order(12)
    void b2_10_jsonAndCsvHaveDifferentEtags() {
        HttpResponse<String> j = get("/api/v1/aois/KR/calendar?start_year=2024&end_year=2025");
        HttpResponse<byte[]> c = bytes("/api/v1/aois/KR/calendar?start_year=2024&end_year=2025", "Accept", "text/csv");
        String jsonTag = j.headers().firstValue("ETag").orElseThrow();
        String csvTag = c.headers().firstValue("ETag").orElseThrow();
        assertThat(jsonTag).isNotEqualTo(csvTag);
        HttpResponse<byte[]> cross = bytes("/api/v1/aois/KR/calendar?start_year=2024&end_year=2025", "Accept", "text/csv",
                "If-None-Match", jsonTag);
        assertThat(cross.statusCode()).isEqualTo(200);
        assertThat(cross.body()).isEqualTo(c.body());
        assertThat(bytes("/api/v1/aois/KR/calendar?start_year=2024&end_year=2025", "Accept", "text/csv", "If-None-Match", csvTag)
                .statusCode()).isEqualTo(304);
        assertThat(get("/api/v1/aois/KR/calendar?start_year=2024&end_year=2025", "If-None-Match", jsonTag).statusCode()).isEqualTo(304);
    }

    @Test
    @Order(13)
    void b2_11_exportAliasIsTheCanonicalCsv() {
        HttpResponse<byte[]> export = bytes("/api/export.csv?type=calendar&aoi_id=KR&start_year=2024&end_year=2025");
        assertThat(export.statusCode()).isEqualTo(200);
        assertThat(export.body()).isEqualTo(bytes("/api/v1/aois/KR/calendar?start_year=2024&end_year=2025", "Accept", "text/csv").body());
        assertThat(export.headers().firstValue("Link").orElse("")).contains("/api/v1/aois/KR/calendar?").contains("rel=\"canonical\"");
        HttpResponse<byte[]> summary = bytes("/api/export.csv?type=summary&aoi_id=KR&start=2025-01-01&end=2025-03-31&freq=month");
        assertThat(summary.statusCode()).isEqualTo(200);
        assertThat(summary.body()).isEqualTo(bytes("/api/v1/aois/KR/summary?start=2025-01-01&end=2025-03-31&freq=month&format=csv").body());
        assertThat(new String(summary.body(), StandardCharsets.UTF_8)).contains("area,date,value");
        assertThat(get("/api/export.csv?type=nope&aoi_id=KR").statusCode()).isEqualTo(404);
    }

    // ── unknown AOIs and Redis down ──

    @Test
    @Order(14)
    void b2_14_unknownAoisNeverReachTheCache() {
        long before = keys.count("");
        for (int i = 0; i < 1000; i++) {
            HttpResponse<String> r = get("/api/v1/aois/NOPE_" + i + "/calendar?start_year=2024&end_year=2025");
            assertThat(r.statusCode()).isEqualTo(404);
            if (i % 250 == 0) {
                assertThat(json(r).get("code").asString()).isEqualTo("unknown_aoi");
            }
        }
        assertThat(keys.count("")).isEqualTo(before);
    }

    @Test
    @Order(15)
    void b2_15_redisDownStillAnswersFromTheDatabase() throws Exception {
        REDIS.stop();
        try {
            for (String aoi : List.of("KR", "SIDO_29")) {
                HttpResponse<String> r = get("/api/v1/aois/" + aoi + "/calendar?start_year=2024&end_year=2025");
                assertThat(r.statusCode()).isEqualTo(200);
                assertCalendarMatches(json(r), V11, true, aoi, 2024, 2025);
            }
            assertThat(metric("emberline_cache_requests_total", "calendar", "error")).isPositive();
        } finally {
            REDIS.start();
            Thread.sleep(500);
        }
        keys.close();
        keys = new ReadSupport.Keys(REDIS.url());
        assertThat(get("/api/v1/aois/KR/calendar?start_year=2024&end_year=2025").statusCode()).isEqualTo(200);
    }

    // ── v1.1 partial month, day series and month residual (R-SP1, R-CD1) ──

    @Test
    @Order(16)
    void partialMonthCarriesProjectionAndUncertainVerdict() {
        JsonNode cal = ok("/api/v1/aois/KR/calendar?start_year=2026&end_year=2026");
        JsonNode nov = cal.get("months").get(10);
        assertThat(nov.get("status").asString()).isEqualTo("partial");
        assertThat(nov.get("value").doubleValue()).isEqualTo(44.336265563964844);
        assertThat(nov.get("value_projected").doubleValue()).isEqualTo(102.31446064148237);
        assertThat(nov.get("as_of_date").asString()).isEqualTo("2026-11-13");
        assertThat(nov.get("value_source").asString()).isEqualTo("n20_bridge");
        assertThat(nov.get("verdict").asString()).isEqualTo("uncertain");
        assertThat(cal.get("months").get(11).get("status").asString()).isEqualTo("out_of_range");
        JsonNode cmp = ok("/api/v1/aois/KR/months/2026-11/comparison");
        assertThat(cmp.get("value").doubleValue()).isEqualTo(44.336265563964844);
        assertThat(cmp.get("value_projected").doubleValue()).isEqualTo(102.31446064148237);
        assertThat(cmp.get("as_of_date").asString()).isEqualTo("2026-11-13");
        assertThat(cmp.get("status").asString()).isEqualTo("partial");
        JsonNode sum = ok("/api/v1/aois/KR/summary?start=2026-11-01&end=2026-11-30&freq=month");
        JsonNode row = sum.get("series").get(0);
        assertThat(row.get("value").doubleValue()).isEqualTo(44.336265563964844);
        assertThat(row.get("value_projected").doubleValue()).isEqualTo(102.31446064148237);
        assertThat(row.get("status").asString()).isEqualTo("partial");
        JsonNode days = ok("/api/v1/aois/KR/summary?start=2026-11-10&end=2026-11-20&freq=day");
        for (JsonNode d : days.get("series")) {
            boolean after = d.get("date").asString().compareTo("2026-11-13") > 0;
            assertThat(d.get("status").asString()).isEqualTo(after ? "out_of_range" : "partial");
        }
        assertThat(days.get("month_residual").get(0).get("month_residual").doubleValue()).isZero();
    }

    @Test
    @Order(17)
    void daySeriesPlusMonthResidualIsTheCellTotalOfTheAoi() throws Exception {
        long[] cells = krCellsByCentre();
        JsonNode s = ok("/api/v1/aois/KR/summary?start=2005-03-01&end=2005-03-31&freq=day");
        assertThat(s.get("series").size()).isEqualTo(31);
        double days = 0;
        for (JsonNode d : s.get("series")) {
            assertThat(d.get("status").asString()).isEqualTo("ok");
            assertThat(d.get("value_source").asString()).isEqualTo("modis_m2v");
            days += d.get("value").doubleValue();
        }
        JsonNode mr = s.get("month_residual").get(0);
        assertThat(mr.get("year").asInt()).isEqualTo(2005);
        assertThat(mr.get("residual_prorated").asBoolean()).isFalse();
        assertThat(mr.get("note").asString()).isEqualTo("MODIS 기간 점으로 표시되지 않는 추정분, 월 단위");
        double residual = mr.get("month_residual").doubleValue();
        assertThat(residual).isPositive();
        String ids = java.util.Arrays.stream(cells).mapToObj(Long::toString).collect(java.util.stream.Collectors.joining(","));
        double cellMonth = ((Number) Duck.query("SELECT sum(value) FROM read_parquet(?) WHERE year = 2005 AND month = 3 AND cell_id IN (" + ids + ")",
                V11.resolve("harmonized_cell_month.parquet")).get(0).get(0)).doubleValue();
        double background = mr.get("by_type").get("modis_m2v_background").doubleValue();
        assertThat(background).isPositive();
        assertThat(days + residual - background)
                .as("Σ day series + month_residual = Σ harmonized_cell_month.value of the AOI's cells + background predictions "
                        + "(background is not in harmonized_cell_month, hand-off §1); files agree to 1e-6 relative")
                .isCloseTo(cellMonth, within(cellMonth * 1e-6));
        double byType = 0;
        for (JsonNode v : mr.get("by_type")) {
            byType += v.doubleValue();
        }
        assertThat(byType).isCloseTo(residual, within(1e-6));
        assertThat(s.get("total").get("value").doubleValue()).isCloseTo(days + residual, within(1e-6));
        assertThat(s.get("total").get("residual_included").doubleValue()).isCloseTo(residual, within(1e-9));
        JsonNode cut = ok("/api/v1/aois/KR/summary?start=2005-03-10&end=2005-04-30&freq=day");
        JsonNode march = cut.get("month_residual").get(0);
        assertThat(march.get("residual_prorated").asBoolean()).isTrue();
        assertThat(march.get("included_days").asInt()).isEqualTo(22);
        assertThat(march.get("residual_included").doubleValue()).isCloseTo(residual * 22 / 31, within(1e-9));
        assertThat(cut.get("total").get("residual_prorated").asBoolean()).isTrue();
        JsonNode month = ok("/api/v1/aois/KR/summary?start=2005-03-10&end=2005-04-30&freq=month");
        JsonNode m3 = month.get("series").get(0);
        assertThat(m3.get("basis").asString()).isEqualTo("cell_day");
        assertThat(m3.get("residual_prorated").asBoolean()).isTrue();
        assertThat(m3.get("flags").toString()).contains("partial_range");
        double dayPart = 0;
        for (JsonNode d : cut.get("series")) {
            if (d.get("date").asString().startsWith("2005-03")) {
                dayPart += d.get("value").doubleValue();
            }
        }
        assertThat(m3.get("value").doubleValue()).isCloseTo(dayPart + residual * 22 / 31, within(1e-6));
        JsonNode m4 = month.get("series").get(1);
        assertThat(m4.get("basis").asString()).isEqualTo("aoi_month");
        assertThat(m4.get("value").doubleValue()).isEqualTo(((Number) ReadSupport.monthRows(V11, true, "KR", 2005, 2005).get(3).get(2)).doubleValue());
    }

    @Test
    @Order(18)
    void terraOnlyDaysHaveNoIntervalAndMissingMonthIsNull() {
        JsonNode s = ok("/api/v1/aois/KR/summary?start=2001-05-30&end=2001-06-02&freq=day");
        JsonNode may30 = s.get("series").get(0);
        assertThat(may30.get("status").asString()).isEqualTo("ok");
        assertThat(may30.get("value").isNumber()).isTrue();
        assertThat(may30.get("value_lo").isNull()).isTrue();
        assertThat(may30.get("flags").toString()).contains("interval_unavailable");
        JsonNode jun1 = s.get("series").get(2);
        assertThat(jun1.get("status").asString()).isEqualTo("missing");
        assertThat(jun1.get("value").isNull()).isTrue();
        assertThat(s.get("total").get("n_missing").asInt()).isEqualTo(2);
        JsonNode cal = ok("/api/v1/aois/KR/calendar?start_year=2001&end_year=2001");
        JsonNode jun = cal.get("months").get(5);
        assertThat(jun.get("value").isNull()).isTrue();
        assertThat(jun.get("raw_modis").asInt()).isEqualTo(11);
        assertThat(jun.get("percentile").doubleValue()).isEqualTo(0.0);
        assertThat(jun.get("verdict").asString()).isEqualTo("uncertain");
    }

    @Test
    @Order(19)
    void b2_18_everyB2ResponseCarriesTheVersionAndVaryHeaders() {
        List<String> paths = List.of("/api/v1/aois/KR/calendar?start_year=2024&end_year=2025",
                "/api/v1/aois/KR/calendar?start_year=2024&end_year=2025&format=csv",
                "/api/v1/aois/KR/months/2025-03/comparison",
                "/api/v1/aois/KR/summary?start=2025-01-01&end=2025-03-31",
                "/api/v1/aois/KR/summary?start=2025-03-01&end=2025-03-05&freq=day",
                "/api/calendar?aoi_id=KR&start_year=2024&end_year=2025", "/api/compare?aoi_id=KR&year=2025&month=3",
                "/api/summary?aoi_id=KR&start=2025-01-01&end=2025-03-31&freq=month",
                "/api/export.csv?type=calendar&aoi_id=KR&start_year=2024&end_year=2025");
        String dataVersion = "v11#" + V11Fixtures.manifestSha(ROOT, "v11").substring(0, 12);
        for (String p : paths) {
            HttpResponse<byte[]> r = bytes(p);
            assertThat(r.statusCode()).as(p).isEqualTo(200);
            assertThat(r.headers().firstValue("X-Method-Version")).as(p).hasValue(MV);
            assertThat(r.headers().firstValue("X-Data-Version")).as(p).hasValue(dataVersion);
            String vary = String.join(",", r.headers().allValues("Vary"));
            assertThat(vary).as(p).contains("Accept").contains("Accept-Language");
            assertThat(r.headers().firstValue("Cache-Control")).as(p).hasValue("public, max-age=60, s-maxage=600");
        }
    }

    @Test
    @Order(20)
    void b1_20_contractParametersOfTheB2Paths() {
        JsonNode doc = ok("/api/v1/openapi.json");
        for (String p : List.of("/api/v1/aois/{aoiId}/calendar", "/api/v1/aois/{aoiId}/months/{yearMonth}/comparison",
                "/api/v1/aois/{aoiId}/summary")) {
            Set<String> served = new TreeSet<>();
            doc.get("paths").get(p).get("get").get("parameters").forEach(n -> served.add(n.get("in").asString() + ":" + n.get("name").asString()));
            assertThat(served).as(p).isEqualTo(ReadSupport.contractParams(p.substring("/api/v1".length())));
        }
        doc.get("paths").properties().forEach(e -> assertThat(e.getKey()).startsWith("/api/v1/"));
    }

    @Test
    @Order(21)
    void v11LoaderStoresProjectionAsOfDateAndCellCentresAsInTheFiles() throws Exception {
        try (java.sql.Connection c = java.sql.DriverManager.getConnection(jdbcUrl, "postgres", "");
             java.sql.Statement st = c.createStatement()) {
            java.sql.ResultSet rs = st.executeQuery("SELECT m.value, m.value_projected, m.as_of_date::text, c.value_projected, c.as_of_date::text "
                    + "FROM harmonized.aoi_month m JOIN harmonized.aoi_month_compare c USING (release_id, aoi_id, year, month) "
                    + "WHERE m.aoi_id = 'KR' AND m.year = 2026 AND m.month = 11 AND m.release_id = "
                    + "(SELECT release_id FROM ingest.active_release WHERE id = 1)");
            assertThat(rs.next()).isTrue();
            assertThat(rs.getDouble(1)).isEqualTo(44.336265563964844);
            assertThat(rs.getDouble(2)).isEqualTo(102.31446064148237);
            assertThat(rs.getString(3)).isEqualTo("2026-11-13");
            assertThat(rs.getDouble(4)).isEqualTo(102.31446064148237);
            assertThat(rs.getString(5)).isEqualTo("2026-11-13");
            rs = st.executeQuery("SELECT count(*) FILTER (WHERE value_projected IS NOT NULL), count(*) FILTER (WHERE as_of_date IS NOT NULL) "
                    + "FROM harmonized.aoi_month WHERE release_id = (SELECT release_id FROM ingest.active_release WHERE id = 1)");
            rs.next();
            List<Object> file = Duck.query("SELECT count(value_projected), count(as_of_date) FROM read_parquet(?)", V11.resolve("aoi_month.parquet")).get(0);
            assertThat(rs.getLong(1)).isEqualTo(((Number) file.get(0)).longValue());
            assertThat(rs.getLong(2)).isEqualTo(((Number) file.get(1)).longValue());
            rs = st.executeQuery("SELECT count(*), sum(cell_lat::float8), sum(cell_lon::float8) FROM harmonized.cell_center "
                    + "WHERE release_id = (SELECT release_id FROM ingest.active_release WHERE id = 1)");
            rs.next();
            List<Object> cells = Duck.query("SELECT count(*), sum(lat::DOUBLE), sum(lon::DOUBLE) FROM (SELECT cell_id, min(cell_lat) lat, min(cell_lon) lon "
                    + "FROM read_parquet(?) GROUP BY cell_id)", V11.resolve("harmonized_cell_month.parquet")).get(0);
            assertThat(rs.getLong(1)).isEqualTo(((Number) cells.get(0)).longValue()).isEqualTo(1863);
            assertThat(rs.getDouble(2)).isCloseTo(((Number) cells.get(1)).doubleValue(), within(1e-6));
            assertThat(rs.getDouble(3)).isCloseTo(((Number) cells.get(2)).doubleValue(), within(1e-6));
        }
    }

    @Test
    @Order(22)
    void rsp3_aRangeThatExcludesNoDataDayIsTheWholeAoiMonthRow() throws Exception {
        // month to date: 2026-11 has as_of_date 2026-11-13, so 11-01..11-14 (and 11-01..11-13) cut no data day
        for (String end : List.of("2026-11-13", "2026-11-14", "2026-11-30")) {
            JsonNode s = ok("/api/v1/aois/KR/summary?start=2026-11-01&end=" + end + "&freq=month");
            JsonNode nov = s.get("series").get(0);
            assertThat(nov.get("basis").asString()).as(end).isEqualTo("aoi_month");
            assertThat(nov.get("value").doubleValue()).as(end).isEqualTo(44.336265563964844);
            assertThat(nov.get("value_projected").doubleValue()).isEqualTo(102.31446064148237);
            assertThat(nov.get("as_of_date").asString()).isEqualTo("2026-11-13");
            assertThat(nov.get("status").asString()).isEqualTo("partial");
            assertThat(nov.get("flags").toString()).doesNotContain("partial_range");
            assertThat(s.get("total").get("value").doubleValue()).isEqualTo(44.336265563964844);
        }
        String csv = get("/api/v1/aois/KR/summary?start=2026-11-01&end=2026-11-14&freq=month&format=csv").body();
        String line = csv.lines().filter(l -> l.startsWith("KR,2026-11-01,")).findFirst().orElseThrow();
        assertThat(line).contains("44.336265563964844").contains(",aoi_month,").doesNotContain("partial_range");
        // a range that cuts a data day (11-13 or 11-01) keeps the cell_day path (R-SP2)
        for (String range : List.of("start=2026-11-01&end=2026-11-12", "start=2026-11-02&end=2026-11-14")) {
            JsonNode nov = ok("/api/v1/aois/KR/summary?" + range + "&freq=month").get("series").get(0);
            assertThat(nov.get("basis").asString()).as(range).isEqualTo("cell_day");
            assertThat(nov.get("flags").toString()).as(range).contains("partial_range");
        }
        // a full past month (as_of_date null) is unchanged; cutting its last day is cell_day
        double march = ((Number) ReadSupport.monthRows(V11, true, "KR", 2025, 2025).get(2).get(2)).doubleValue();
        JsonNode full = ok("/api/v1/aois/KR/summary?start=2025-03-01&end=2025-03-31&freq=month").get("series").get(0);
        assertThat(full.get("basis").asString()).isEqualTo("aoi_month");
        assertThat(full.get("value").doubleValue()).isEqualTo(march);
        JsonNode cut = ok("/api/v1/aois/KR/summary?start=2025-03-01&end=2025-03-30&freq=month").get("series").get(0);
        assertThat(cut.get("basis").asString()).isEqualTo("cell_day");
        assertThat(cut.get("flags").toString()).contains("partial_range");
    }

    /** The /cells feature property keys before pl-fix2, in order (a complete month must keep exactly these). */
    static final List<String> CELL_KEYS = List.of("cell_id", "cell_lat", "cell_lon", "value", "value_lo", "value_hi",
            "raw_modis", "raw_viirs", "value_source", "data_source", "status", "interval_out_of_range", "interval_unavailable",
            "n_events_viirs", "event_area_max_km2_viirs", "event_days_max_viirs", "event_area_share_max_viirs",
            "n_events_modis", "event_area_max_km2_modis", "event_days_max_modis", "event_area_share_max_modis");

    @Test
    @Order(23)
    void fix2_partialCellCarriesValueProjectedAndAsOfDate() throws Exception {
        // the real v1.1 cell file has value_projected but no as_of_date: the loader takes the partial month's as_of_date
        // from aoi_month.parquet
        List<String> cellColumns = Duck.query("SELECT column_name FROM (DESCRIBE SELECT * FROM read_parquet(?))",
                V11.resolve("harmonized_cell_month.parquet")).stream().map(r -> (String) r.get(0)).toList();
        assertThat(cellColumns).contains("value_projected").doesNotContain("as_of_date");
        String asOf = (String) Duck.query("SELECT CAST(max(as_of_date) AS VARCHAR) FROM read_parquet(?) WHERE status = 'partial' "
                + "AND year = 2026 AND month = 11", V11.resolve("aoi_month.parquet")).get(0).get(0);
        assertThat(asOf).isEqualTo("2026-11-13");
        java.util.Map<Long, Double> projected = new java.util.HashMap<>();
        for (List<Object> r : Duck.query("SELECT cell_id::BIGINT, value_projected FROM read_parquet(?) WHERE year = 2026 AND month = 11 "
                + "AND status = 'partial'", V11.resolve("harmonized_cell_month.parquet"))) {
            projected.put(((Number) r.get(0)).longValue(), ((Number) r.get(1)).doubleValue());
        }
        JsonNode fc = ok("/api/v1/cells?aoi_id=KR&month=2026-11");
        assertThat(fc.get("properties").get("month_status").asString()).isEqualTo("partial");
        assertThat(fc.get("features").size()).isPositive();
        for (JsonNode f : fc.get("features")) {
            JsonNode p = f.get("properties");
            assertThat(p.get("status").asString()).isEqualTo("partial");
            assertThat(p.get("value_projected").doubleValue()).as("cell %d", f.get("id").asLong())
                    .isEqualTo(projected.get(f.get("id").asLong()));
            assertThat(p.get("as_of_date").asString()).isEqualTo(asOf);
            List<String> keys = new ArrayList<>();
            p.properties().forEach(e -> keys.add(e.getKey()));
            List<String> expected = new ArrayList<>(CELL_KEYS);
            expected.addAll(expected.indexOf("status") + 1, List.of("value_projected", "as_of_date"));
            assertThat(keys).containsExactlyElementsOf(expected);
        }
        // stored for partial rows only
        try (java.sql.Connection c = java.sql.DriverManager.getConnection(jdbcUrl, "postgres", "");
             java.sql.Statement st = c.createStatement()) {
            java.sql.ResultSet rs = st.executeQuery("SELECT count(*) FILTER (WHERE value_projected IS NOT NULL), "
                    + "count(*) FILTER (WHERE as_of_date = DATE '2026-11-13'), count(*) FILTER (WHERE status <> 'partial' "
                    + "AND (value_projected IS NOT NULL OR as_of_date IS NOT NULL)) FROM observation.cell_month "
                    + "WHERE release_id = (SELECT release_id FROM ingest.active_release WHERE id = 1)");
            rs.next();
            assertThat(rs.getLong(1)).isEqualTo(projected.size()).isEqualTo(22);
            assertThat(rs.getLong(2)).isEqualTo(22);
            assertThat(rs.getLong(3)).isZero();
        }
    }

    @Test
    @Order(24)
    void fix2_completeMonthCellsHaveNeitherField() {
        for (String month : List.of("2025-03", "2026-09", "2001-06")) { // ok, nrt, missing
            HttpResponse<String> r = get("/api/v1/cells?aoi_id=KR&month=" + month);
            assertThat(r.statusCode()).isEqualTo(200);
            assertThat(r.body()).as(month).doesNotContain("value_projected").doesNotContain("as_of_date");
            JsonNode fc = json(r);
            if (!month.equals("2001-06")) {
                assertThat(fc.get("features").size()).as(month).isPositive();
            }
            for (JsonNode f : fc.get("features")) {
                List<String> keys = new ArrayList<>();
                f.get("properties").properties().forEach(e -> keys.add(e.getKey()));
                assertThat(keys).as(month).containsExactlyElementsOf(CELL_KEYS);
            }
        }
    }

    @Test
    @Order(30)
    void b2_17_releaseSwitchUsesANewKeyWithTheNewDataVersion() throws Exception {
        String coreSha = Fixtures.sha256(ROOT.resolve("core/SHA256SUMS"));
        assertThat(IngestCli.run(jdbcUrl, ROOT, "stage", "core").exit()).isZero();
        assertThat(IngestCli.run(jdbcUrl, ROOT, "activate", "core", "--expect-sha256", coreSha).exit()).isZero();
        assertThat(waitForRelease("core")).isTrue();
        HttpResponse<String> r = get("/api/v1/aois/KR/calendar?start_year=2024&end_year=2025");
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.headers().firstValue("X-Data-Version")).hasValue("core#" + coreSha.substring(0, 12));
        assertCalendarMatches(json(r), Fixtures.releaseRoot().resolve("core"), false, "KR", 2024, 2025);
        assertThat(keys.keys(":c:calendar:")).contains("em:test:c:calendar:s2:" + MV + ":" + coreSha.substring(0, 8) + ":KR");
        HttpResponse<String> day = get("/api/v1/aois/KR/summary?start=2025-03-01&end=2025-03-05&freq=day");
        assertThat(day.statusCode()).as("v1 release has no harmonized_cell_day").isEqualTo(503);
        assertThat(json(day).get("code").asString()).isEqualTo("data_not_loaded");
    }

    // ── helpers ──

    /** Cells whose centre lies inside the KR boundary — computed here from the fixture files with JTS. */
    static long[] krCellsByCentre() throws Exception {
        JsonNode root = JSON.readTree(V11.resolve("aoi_boundaries.geojson").toFile());
        Geometry kr = null;
        GeometryFactory f = new GeometryFactory();
        for (JsonNode feature : root.get("features")) {
            if ("KR".equals(feature.get("properties").get("aoi_id").asString())) {
                JsonNode g = feature.get("geometry");
                List<Polygon> parts = new ArrayList<>();
                JsonNode polys = "Polygon".equals(g.get("type").asString()) ? JSON.createArrayNode().add(g.get("coordinates")) : g.get("coordinates");
                for (JsonNode poly : polys) {
                    List<LinearRing> rings = new ArrayList<>();
                    for (JsonNode ring : poly) {
                        List<Coordinate> cs = new ArrayList<>();
                        ring.forEach(xy -> cs.add(new Coordinate(xy.get(0).doubleValue(), xy.get(1).doubleValue())));
                        rings.add(f.createLinearRing(cs.toArray(new Coordinate[0])));
                    }
                    parts.add(f.createPolygon(rings.get(0), rings.subList(1, rings.size()).toArray(new LinearRing[0])));
                }
                kr = f.createMultiPolygon(parts.toArray(new Polygon[0]));
            }
        }
        PreparedGeometry p = PreparedGeometryFactory.prepare(kr);
        List<Long> ids = new ArrayList<>();
        for (List<Object> c : Duck.query("SELECT cell_id::BIGINT, min(cell_lat), min(cell_lon) FROM read_parquet(?) GROUP BY 1",
                V11.resolve("harmonized_cell_month.parquet"))) {
            if (p.contains(f.createPoint(new Coordinate(((Number) c.get(2)).floatValue(), ((Number) c.get(1)).floatValue())))) {
                ids.add(((Number) c.get(0)).longValue());
            }
        }
        return ids.stream().mapToLong(Long::longValue).toArray();
    }
}

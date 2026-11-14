package io.emberline.hotspots;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import io.emberline.support.Contract;
import io.emberline.platform.flyway.MultiSchemaFlywayRunner;
import io.emberline.support.Duck;
import io.emberline.support.EmbeddedPg;
import io.emberline.support.Fixtures;
import io.emberline.support.IngestCli;
import io.emberline.support.ReleaseFixtures;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * B3 /hotspots over real HTTP. Release {@code core} (real values) is activated without points first (503), then the
 * owner fixture core-v11/harmonized_hotspots (KR cells, real points) is copied next to it, plus two derived months:
 * 2001-03 = 2004-03 shifted back three years with null bounds (Terra-only shape: the fixture has no Terra-only month)
 * and 2001-04 = 2004-04 shifted with every row doubled (ties on the four public sort columns). Expected values come
 * from DuckDB over the parquet files (and core-v11 harmonized_cell_day), not from the code under test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"management.server.port=0", "emberline.ratelimit.enabled=false"})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestPropertySource(properties = "emberline.release.poll-interval=1s")
class HotspotsApiTest {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    static final Path ROOT = ReleaseFixtures.newRoot();
    static final Path V11 = Fixtures.releaseRoot().resolve("core-v11");
    static final Path POINTS = ROOT.resolve("core/harmonized_hotspots");
    static final String KR_BBOX = "124,33,132,39";
    static final String MV = "v1-m2v-2026-11-14";

    @LocalServerPort
    int port;

    @Value("${spring.datasource.url}")
    String jdbcUrl;

    /** As AppTestBase, but the release root is {@link #ROOT} (a subclass cannot override the base class's root). */
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        String url = EmbeddedPg.newDatabase();
        MultiSchemaFlywayRunner.migrateAll(EmbeddedPg.dataSource(url));
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("emberline.release.root", ROOT::toString);
    }

    @BeforeAll
    void activate() throws InterruptedException {
        assertThat(IngestCli.run(jdbcUrl, ROOT, "stage", "core").exit()).isZero();
        assertThat(IngestCli.run(jdbcUrl, ROOT, "activate", "core", "--expect-sha256", Fixtures.coreManifestSha256()).exit()).isZero();
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline && json(get("/api/v1/version")).get("release").isNull()) {
            Thread.sleep(200);
        }
    }

    HttpResponse<String> get(String path, String... headers) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET();
        for (int i = 0; i + 1 < headers.length; i += 2) {
            builder.header(headers[i], headers[i + 1]);
        }
        try {
            return HTTP.send(builder.timeout(Duration.ofSeconds(30)).build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    static JsonNode json(HttpResponse<String> response) {
        return JSON.readTree(response.body());
    }

    static String files(String layer, int year, int month) {
        return POINTS.resolve(layer).resolve("year=" + year).resolve(String.format("month=%02d", month)).resolve("*.parquet").toString();
    }

    static long count(String sql, Object... params) {
        return ((Number) Duck.query(sql, params).get(0).get(0)).longValue();
    }

    @Test
    @Order(1)
    void b3_missingFolderIs503NotReady() {
        HttpResponse<String> r = get("/api/v1/hotspots?bbox=" + KR_BBOX + "&start=2025-03-01&end=2025-03-31");
        assertThat(r.statusCode()).isEqualTo(503);
        JsonNode p = json(r);
        assertThat(p.get("code").asString()).isEqualTo("data_not_loaded");
        assertThat(p.get("error_no").asInt()).isEqualTo(50501);
        assertThat(p.get("detail").asString()).contains("준비 중");
        copyPoints();
        HttpResponse<String> ready = get("/api/v1/hotspots?bbox=" + KR_BBOX + "&start=2025-03-01&end=2025-03-31");
        assertThat(ready.statusCode()).as(ready.body() + " root " + ROOT).isEqualTo(200);
    }

    @Test
    @Order(2)
    void b3_perLayerCountsForAKrBboxMonth() {
        String where = "WHERE date_local BETWEEN DATE '2025-03-01' AND DATE '2025-03-31' AND latitude BETWEEN 33 AND 39 AND longitude BETWEEN 124 AND 132";
        long ref = count("SELECT count(*) FROM read_parquet(?) " + where, files("reference", 2025, 3));
        long other = count("SELECT count(*) FROM read_parquet(?) " + where, files("other", 2025, 3));
        assertThat(ref).isPositive();
        assertThat(other).isPositive();

        long t0 = System.nanoTime();
        JsonNode reference = json(get("/api/v1/hotspots?bbox=" + KR_BBOX + "&start=2025-03-01&end=2025-03-31"));
        long ms = (System.nanoTime() - t0) / 1_000_000;
        System.out.println("TIMING KR bbox one month (layer=reference, limit=1000): " + ms + " ms, total=" + reference.get("total"));
        assertThat(reference.get("layer").asString()).isEqualTo("reference");
        assertThat(reference.get("total").asLong()).isEqualTo(ref);
        assertThat(reference.get("reference_sum").get("n_points").asLong()).isEqualTo(ref);
        assertThat(reference.get("count").asInt()).isEqualTo((int) Math.min(ref, 1000));
        assertThat(reference.get("limit").asInt()).isEqualTo(1000);
        assertThat(reference.get("method_version").asString()).isEqualTo(MV);
        assertThat(reference.get("unit").asString()).isEqualTo("VIIRS(S-NPP) 기준 화재 감지량");
        assertThat(reference.has("aoi_id")).isFalse();
        assertThat(reference.get("bbox").toString()).isEqualTo("[124.0,33.0,132.0,39.0]");
        reference.get("hotspots").forEach(h -> assertThat(h.get("is_reference").asBoolean()).isTrue());

        JsonNode all = json(get("/api/v1/hotspots?bbox=" + KR_BBOX + "&start=2025-03-01&end=2025-03-31&layer=all&limit=10000"));
        assertThat(all.get("total").asLong()).isEqualTo(ref + other).isGreaterThan(reference.get("total").asLong());
        assertThat(all.get("count").asLong()).isEqualTo(ref + other);
        assertThat(all.get("truncated").asBoolean()).isFalse();
        assertThat(all.get("next_cursor").isNull()).isTrue();
        // layer=all still sums reference points only
        assertThat(all.get("reference_sum")).isEqualTo(reference.get("reference_sum"));
        long others = 0;
        for (JsonNode h : all.get("hotspots")) {
            others += h.get("is_reference").asBoolean() ? 0 : 1;
        }
        assertThat(others).isEqualTo(other);
    }

    @Test
    @Order(3)
    void b3_referenceSumEqualsCellDayForTheSameCellsAndDates() {
        // cells with corner lat 36.0..37.9 and lon 126.0..127.9 = points within [126.0, 127.99999] x [36.0, 37.99999]
        // (5-decimal coordinates). Day 1 of a VIIRS-era month is avoided: there cell_day has n_points = 0 for keys
        // that do have reference points (68 keys in the fixture, all on day 1 — reported, not ours to fix).
        String cells = "(cell_id // 3600 - 900) BETWEEN 360 AND 379 AND (cell_id % 3600 - 1800) BETWEEN 1260 AND 1279";
        for (String[] range : List.of(new String[] {"2025-03-02", "2025-03-31"}, new String[] {"2004-03-01", "2004-04-30"},
                new String[] {"2012-03-02", "2012-06-01"})) { // ≤ 92 days (max-days-reference)
            List<Object> expected = Duck.query("SELECT sum(value), sum(value_lo), sum(value_hi), sum(n_points) FROM read_parquet(?) "
                    + "WHERE " + cells + " AND date_local BETWEEN ?::DATE AND ?::DATE", V11.resolve("harmonized_cell_day.parquet"),
                    range[0], range[1]).get(0);
            JsonNode sum = json(get("/api/v1/hotspots?bbox=126.0,36.0,127.99999,37.99999&start=" + range[0] + "&end=" + range[1]
                    + "&limit=1")).get("reference_sum");
            assertThat(sum.get("n_points").asLong()).as("n_points %s", (Object) range).isEqualTo(((Number) expected.get(3)).longValue());
            assertThat(sum.get("value").asDouble()).as("value %s", (Object) range).isEqualTo(((Number) expected.get(0)).doubleValue());
            assertThat(sum.get("value_lo").asDouble()).isEqualTo(((Number) expected.get(1)).doubleValue());
            assertThat(sum.get("value_hi").asDouble()).isEqualTo(((Number) expected.get(2)).doubleValue());
            assertThat(sum.get("n_points").asLong()).isPositive();
            assertThat(sum.get("note").asString()).contains("/summary");
        }
    }

    @Test
    @Order(4)
    void b3_terraOnlyMonthHasNullBounds() {
        JsonNode terra = json(get("/api/v1/hotspots?bbox=" + KR_BBOX + "&start=2001-03-01&end=2001-03-31&limit=5"));
        JsonNode sum = terra.get("reference_sum");
        assertThat(sum.get("n_points").asLong()).isEqualTo(count("SELECT count(*) FROM read_parquet(?)", files("reference", 2001, 3)));
        assertThat(sum.get("value").asDouble()).isPositive();
        assertThat(sum.get("value_lo").isNull()).isTrue();
        assertThat(sum.get("value_hi").isNull()).isTrue();
        assertThat(terra.get("hotspots").get(0).get("weight_lo").isNull()).isTrue();
        assertThat(terra.get("hotspots").get(0).get("weight_source").asString()).isEqualTo("modis_m2v_terra");
        JsonNode modis = json(get("/api/v1/hotspots?bbox=" + KR_BBOX + "&start=2004-03-01&end=2004-03-31&limit=1")).get("reference_sum");
        assertThat(modis.get("value_lo").isNumber()).isTrue();
        assertThat(modis.get("value_hi").isNumber()).isTrue();
        // a range that mixes a null-bound month with a bounded one → null bounds
        JsonNode mixed = json(get("/api/v1/hotspots?bbox=" + KR_BBOX + "&start=2001-03-15&end=2001-04-15&limit=1")).get("reference_sum");
        assertThat(mixed.get("value_lo").isNull()).isTrue();
    }

    @Test
    @Order(5)
    void b3_cursorWalkReturnsEveryPointOnceInOrder() {
        // layer=all spans at most 31 days; this one still crosses a month folder boundary
        walk("bbox=" + KR_BBOX + "&start=2025-03-16&end=2025-04-15&layer=all", 97,
                count("SELECT count(*) FROM read_parquet([?, ?, ?, ?]) WHERE date_local BETWEEN DATE '2025-03-16' AND DATE '2025-04-15'",
                        files("reference", 2025, 3), files("reference", 2025, 4), files("other", 2025, 3), files("other", 2025, 4)));
        // 2001-04: every row twice with the same (acq_datetime_utc, latitude, longitude, satellite) — pages split ties
        walk("bbox=" + KR_BBOX + "&start=2001-04-01&end=2001-04-30", 7, count("SELECT count(*) FROM read_parquet(?)", files("reference", 2001, 4)));
    }

    void walk(String query, int limit, long expected) {
        List<String> seen = new ArrayList<>();
        Set<String> unique = new HashSet<>();
        String cursor = null;
        String previousKey = null;
        int pages = 0;
        do {
            JsonNode page = json(get("/api/v1/hotspots?" + query + "&limit=" + limit + (cursor == null ? "" : "&cursor=" + cursor)));
            assertThat(page.get("total").asLong()).isEqualTo(expected);
            for (JsonNode h : page.get("hotspots")) {
                String key = h.get("acq_datetime_utc").asString() + "|" + String.format("%012.5f|%012.5f", h.get("latitude").asDouble() + 90,
                        h.get("longitude").asDouble() + 180) + "|" + h.get("satellite").asString();
                assertThat(previousKey == null || previousKey.compareTo(key) <= 0).as("order at %s", key).isTrue();
                previousKey = key;
                seen.add(h.toString());
                unique.add(h.toString());
            }
            cursor = page.get("next_cursor").isNull() ? null : page.get("next_cursor").asString();
            assertThat(page.get("truncated").asBoolean()).isEqualTo(cursor != null);
            pages++;
        } while (cursor != null && pages < 10_000);
        assertThat(seen).hasSize((int) expected);
        assertThat(unique).hasSize((int) expected);
        assertThat(pages).isEqualTo((int) ((expected + limit - 1) / limit));
    }

    @Test
    @Order(6)
    void b3_tooWideAreaOrSpanIs400PointingToCells() {
        HttpResponse<String> area = get("/api/v1/hotspots?bbox=100,20,120,40&start=2025-03-01&end=2025-03-31");
        assertThat(area.statusCode()).isEqualTo(400);
        assertThat(json(area).get("code").asString()).isEqualTo("range_too_large");
        assertThat(json(area).get("error_no").asInt()).isEqualTo(40101);
        assertThat(json(area).get("param").asString()).isEqualTo("bbox");
        assertThat(json(area).get("detail").asString()).contains("/cells");
        // spans per layer = /version limits (contract Version.limits 92 / 31, one setting emberline.hotspots.*)
        JsonNode limits = json(get("/api/v1/version")).get("limits");
        assertThat(limits.get("hotspots_days_reference").asInt()).isEqualTo(92);
        assertThat(limits.get("hotspots_days_all").asInt()).isEqualTo(31);
        HttpResponse<String> span = get("/api/v1/hotspots?bbox=" + KR_BBOX + "&start=2024-01-01&end=2024-04-02");
        assertThat(span.statusCode()).as("93 days, layer=reference").isEqualTo(400);
        assertThat(json(span).get("error_no").asInt()).isEqualTo(40101);
        assertThat(json(span).get("param").asString()).isEqualTo("end");
        assertThat(json(span).get("detail").asString()).contains("/cells").contains("92");
        assertThat(get("/api/v1/hotspots?bbox=" + KR_BBOX + "&start=2024-01-01&end=2024-04-01&limit=1").statusCode()).as("92 days (2024 is a leap year)").isEqualTo(200);
        HttpResponse<String> all = get("/api/v1/hotspots?bbox=" + KR_BBOX + "&start=2025-03-01&end=2025-04-01&layer=all");
        assertThat(all.statusCode()).as("32 days, layer=all").isEqualTo(400);
        assertThat(json(all).get("param").asString()).isEqualTo("end");
        assertThat(json(all).get("detail").asString()).contains("31");
        assertThat(get("/api/v1/hotspots?bbox=" + KR_BBOX + "&start=2025-03-01&end=2025-03-31&layer=all&limit=1").statusCode()).as("31 days").isEqualTo(200);
        assertThat(get("/api/v1/hotspots?bbox=120,30,130,40&start=2025-03-01&end=2025-03-31").statusCode()).isEqualTo(200);
        HttpResponse<String> japan = get("/api/v1/hotspots?aoi_id=ADM0_JPN&start=2025-03-01&end=2025-03-31");
        assertThat(json(japan).get("error_no").asInt()).isEqualTo(40101);
        assertThat(json(japan).get("param").asString()).isEqualTo("aoi_id");
    }

    @Test
    @Order(7)
    void b3_aoiIdSelectsPointsInsideTheBoundary() {
        Geometry boundary = Boundaries.of(Fixtures.releaseRoot().resolve("core/aoi_boundaries.geojson"), "SIDO_47");
        GeometryFactory gf = new GeometryFactory();
        List<List<Object>> rows = Duck.query("SELECT latitude, longitude, is_reference, harmonized_weight::DOUBLE FROM read_parquet([?, ?]) "
                + "WHERE date_local BETWEEN DATE '2025-03-01' AND DATE '2025-03-31'", files("reference", 2025, 3), files("other", 2025, 3));
        long inside = 0;
        long insideRef = 0;
        double value = 0;
        for (List<Object> r : rows) {
            double lat = (Double) r.get(0);
            double lon = (Double) r.get(1);
            if (boundary.contains(gf.createPoint(new Coordinate(lon, lat)))) {
                inside++;
                if ((Boolean) r.get(2)) {
                    insideRef++;
                    value += (Double) r.get(3);
                }
            }
        }
        assertThat(inside).isPositive().isLessThan(rows.size());
        JsonNode body = json(get("/api/v1/hotspots?aoi_id=SIDO_47&start=2025-03-01&end=2025-03-31&layer=all&limit=10000"));
        assertThat(body.get("aoi_id").asString()).isEqualTo("SIDO_47");
        assertThat(body.has("bbox")).isFalse();
        assertThat(body.get("total").asLong()).isEqualTo(inside);
        assertThat(body.get("count").asLong()).isEqualTo(inside);
        assertThat(body.get("reference_sum").get("n_points").asLong()).isEqualTo(insideRef);
        assertThat(body.get("reference_sum").get("value").asDouble()).isCloseTo(value, within(1e-9));
        for (JsonNode h : body.get("hotspots")) {
            assertThat(boundary.contains(gf.createPoint(new Coordinate(h.get("longitude").asDouble(), h.get("latitude").asDouble())))).isTrue();
        }
        // paging with the AOI filter
        JsonNode first = json(get("/api/v1/hotspots?aoi_id=SIDO_47&start=2025-03-01&end=2025-03-31&layer=all&limit=10"));
        assertThat(first.get("count").asInt()).isEqualTo(10);
        JsonNode second = json(get("/api/v1/hotspots?aoi_id=SIDO_47&start=2025-03-01&end=2025-03-31&layer=all&limit=10&cursor="
                + first.get("next_cursor").asString()));
        assertThat(second.get("hotspots").get(0)).isEqualTo(body.get("hotspots").get(10));
        assertThat(get("/api/v1/hotspots?aoi_id=SIDO_99&start=2025-03-01&end=2025-03-31").statusCode()).isEqualTo(404);
    }

    @Test
    @Order(8)
    @SuppressWarnings("unchecked")
    void b3_itemFieldsFollowTheFileAndTheContract() {
        JsonNode body = json(get("/api/v1/hotspots?bbox=" + KR_BBOX + "&start=2025-03-01&end=2025-03-31&limit=1"));
        JsonNode h = body.get("hotspots").get(0);
        List<String> fields = new ArrayList<>();
        h.properties().forEach(e -> fields.add(e.getKey()));
        List<String> required = (List<String>) Contract.at("components", "schemas", "Hotspot").get("required");
        assertThat(fields).containsExactlyElementsOf(required);
        assertThat(fields).contains("adm0_a3", "adm1_code").doesNotContain("country", "adm1");
        List<Object> first = Duck.query("SELECT latitude, longitude, epoch_us(acq_datetime_utc), "
                + "CAST(date_local AS VARCHAR), satellite, cell_id, adm0_a3, adm1_code, weight_source, source FROM read_parquet(?) "
                + "WHERE latitude BETWEEN 33 AND 39 AND longitude BETWEEN 124 AND 132 "
                + "ORDER BY acq_datetime_utc, latitude, longitude, satellite LIMIT 1", files("reference", 2025, 3)).get(0);
        assertThat(h.get("latitude").asDouble()).isEqualTo((Double) first.get(0));
        assertThat(h.get("longitude").asDouble()).isEqualTo((Double) first.get(1));
        assertThat(h.get("acq_datetime_utc").asString()).endsWith("Z");
        assertThat(java.time.Instant.parse(h.get("acq_datetime_utc").asString()).toEpochMilli() * 1000).isEqualTo(((Number) first.get(2)).longValue());
        assertThat(h.get("date_local").asString()).isEqualTo(first.get(3));
        assertThat(h.get("satellite").asString()).isEqualTo(first.get(4));
        assertThat(h.get("cell_id").asLong()).isEqualTo(((Number) first.get(5)).longValue());
        assertThat(h.get("adm0_a3").asString()).isEqualTo(first.get(6));
        assertThat(h.get("adm1_code").asString()).isEqualTo(first.get(7));
        assertThat(h.get("weight_source").asString()).isEqualTo(first.get(8));
        assertThat(h.get("source").asString()).isEqualTo(first.get(9));
        List<String> top = new ArrayList<>();
        body.properties().forEach(e -> top.add(e.getKey()));
        Map<String, Object> listProps = (Map<String, Object>) Contract.at("components", "schemas", "HotspotList").get("properties");
        assertThat(listProps.keySet()).containsAll(top);
        Map<String, Object> sumProps = (Map<String, Object>) ((Map<String, Object>) listProps.get("reference_sum")).get("properties");
        List<String> sumFields = new ArrayList<>();
        body.get("reference_sum").properties().forEach(e -> sumFields.add(e.getKey()));
        assertThat(sumProps.keySet()).containsAll(sumFields);
    }

    @Test
    @Order(9)
    @SuppressWarnings("unchecked")
    void b3_csvHasTheSameColumnsAndRows() {
        String q = "bbox=" + KR_BBOX + "&start=2025-03-01&end=2025-03-31&layer=all&limit=50";
        JsonNode body = json(get("/api/v1/hotspots?" + q));
        for (HttpResponse<String> csv : List.of(get("/api/v1/hotspots?" + q + "&format=csv"), get("/api/v1/hotspots?" + q, "Accept", "text/csv"))) {
            assertThat(csv.statusCode()).isEqualTo(200);
            assertThat(csv.headers().firstValue("Content-Type").orElse("")).startsWith("text/csv");
            assertThat(csv.headers().firstValue("Content-Disposition").orElse(""))
                    .contains("attachment").contains("emberline_hotspots_124.0_33.0_132.0_39.0_2025-03-01_2025-03-31.csv");
            List<String> lines = csv.body().lines().toList();
            Map<String, Object> media = (Map<String, Object>) ((Map<String, Object>) ((Map<String, Object>) ((Map<String, Object>) ((Map<String, Object>)
                    Contract.at("paths", "/hotspots", "get").get("responses")).get("200")).get("content")).get("text/csv")).get("schema");
            assertThat(List.of(lines.get(0).split(","))).containsExactlyElementsOf((List<String>) media.get("x-csv-columns"));
            assertThat(lines).hasSize(1 + body.get("count").asInt());
            JsonNode h = body.get("hotspots").get(0);
            String[] cells = lines.get(1).split(",", -1);
            assertThat(cells[2]).isEqualTo(h.get("acq_datetime_utc").asString());
            assertThat(Double.parseDouble(cells[0])).isEqualTo(h.get("latitude").asDouble());
            assertThat(cells[10]).isEqualTo(h.get("adm0_a3").asString());
        }
        // R-FMT1: one negotiation path (HarmonizedWebConfig favorParameter "format"), an unknown format is 406 API-wide.
        assertThat(get("/api/v1/hotspots?" + q + "&format=xml").statusCode()).isEqualTo(406);
    }

    @Test
    @Order(10)
    void b3_validationAndAlias() {
        assertThat(json(get("/api/v1/hotspots?start=2025-03-01&end=2025-03-31")).get("param").asString()).isEqualTo("aoi_id");
        assertThat(json(get("/api/v1/hotspots?bbox=" + KR_BBOX + "&aoi_id=KR&start=2025-03-01&end=2025-03-31")).get("param").asString()).isEqualTo("aoi_id");
        assertThat(json(get("/api/v1/hotspots?bbox=" + KR_BBOX + "&start=2025-03-31&end=2025-03-01")).get("param").asString()).isEqualTo("end");
        assertThat(json(get("/api/v1/hotspots?bbox=" + KR_BBOX + "&start=2025-02-30&end=2025-03-01")).get("param").asString()).isEqualTo("start");
        assertThat(json(get("/api/v1/hotspots?bbox=" + KR_BBOX + "&end=2025-03-01")).get("param").asString()).isEqualTo("start");
        assertThat(json(get("/api/v1/hotspots?bbox=132,33,124,39&start=2025-03-01&end=2025-03-02")).get("param").asString()).isEqualTo("bbox");
        assertThat(json(get("/api/v1/hotspots?bbox=1,2,3&start=2025-03-01&end=2025-03-02")).get("param").asString()).isEqualTo("bbox");
        assertThat(json(get("/api/v1/hotspots?bbox=" + KR_BBOX + "&start=2025-03-01&end=2025-03-02&layer=other")).get("param").asString()).isEqualTo("layer");
        assertThat(json(get("/api/v1/hotspots?bbox=" + KR_BBOX + "&start=2025-03-01&end=2025-03-02&limit=0")).get("param").asString()).isEqualTo("limit");
        assertThat(json(get("/api/v1/hotspots?bbox=" + KR_BBOX + "&start=2025-03-01&end=2025-03-02&limit=10001")).get("param").asString()).isEqualTo("limit");
        assertThat(json(get("/api/v1/hotspots?bbox=" + KR_BBOX + "&start=2025-03-01&end=2025-03-02&cursor=abc")).get("param").asString()).isEqualTo("cursor");
        String c = json(get("/api/v1/hotspots?bbox=" + KR_BBOX + "&start=2025-03-01&end=2025-03-31&limit=1")).get("next_cursor").asString();
        HttpResponse<String> other = get("/api/v1/hotspots?bbox=" + KR_BBOX + "&start=2025-03-01&end=2025-03-30&limit=1&cursor=" + c);
        assertThat(other.statusCode()).isEqualTo(400);
        assertThat(json(other).get("param").asString()).isEqualTo("cursor");
        // a month without a folder is simply empty
        JsonNode empty = json(get("/api/v1/hotspots?bbox=" + KR_BBOX + "&start=2015-03-01&end=2015-03-31"));
        assertThat(empty.get("total").asLong()).isZero();
        assertThat(empty.get("reference_sum").get("value").asDouble()).isZero();
        assertThat(empty.get("reference_sum").get("value_lo").asDouble()).isZero();
        String q = "?bbox=" + KR_BBOX + "&start=2025-03-01&end=2025-03-31&limit=3";
        HttpResponse<String> alias = get("/api/hotspots" + q);
        assertThat(alias.statusCode()).isEqualTo(200);
        assertThat(json(alias)).isEqualTo(json(get("/api/v1/hotspots" + q)));
        assertThat(alias.headers().firstValue("Link")).hasValue("</api/v1/hotspots" + q + ">; rel=\"canonical\"");
        HttpResponse<String> r = get("/api/v1/hotspots" + q);
        assertThat(r.headers().firstValue("ETag")).isPresent();
        assertThat(get("/api/v1/hotspots" + q, "If-None-Match", r.headers().firstValue("ETag").orElseThrow()).statusCode()).isEqualTo(304);
    }

    // pl-fix2 item 2: /api/export.csv?type=hotspots (contract GET /export.csv = 「위 결과를 CSV로」)

    static final String EXPORT_Q = "bbox=" + KR_BBOX + "&start=2025-03-01&end=2025-03-31&layer=all&limit=50";

    @Test
    @Order(11)
    @SuppressWarnings("unchecked")
    void fix2_exportHotspotsHeaderLineIsTheContractColumnOrder() {
        HttpResponse<String> csv = get("/api/export.csv?type=hotspots&" + EXPORT_Q);
        assertThat(csv.statusCode()).as(csv.body()).isEqualTo(200);
        assertThat(csv.headers().firstValue("Content-Type").orElse("")).startsWith("text/csv");
        assertThat(csv.headers().firstValue("Content-Disposition").orElse(""))
                .contains("attachment").contains("emberline_hotspots_124.0_33.0_132.0_39.0_2025-03-01_2025-03-31.csv");
        assertThat(csv.headers().firstValue("Link")).hasValue("</api/v1/hotspots?" + EXPORT_Q.replace(",", "%2C")
                + "&format=csv>; rel=\"canonical\"");
        String header = csv.body().lines().findFirst().orElseThrow();
        assertThat(header).isEqualTo("latitude,longitude,acq_datetime_utc,date_local,satellite,instrument,frp,confidence,daynight,"
                + "cell_id,adm0_a3,adm1_code,harmonized_weight,weight_lo,weight_hi,weight_source,is_reference,source,method_version");
        Map<String, Object> media = (Map<String, Object>) ((Map<String, Object>) ((Map<String, Object>) ((Map<String, Object>) ((Map<String, Object>)
                Contract.at("paths", "/hotspots", "get").get("responses")).get("200")).get("content")).get("text/csv")).get("schema");
        assertThat(List.of(header.split(","))).containsExactlyElementsOf((List<String>) media.get("x-csv-columns"));
        // the export is the canonical CSV byte for byte
        assertThat(csv.body()).isEqualTo(get("/api/v1/hotspots?" + EXPORT_Q + "&format=csv").body());
    }

    @Test
    @Order(12)
    void fix2_exportHotspotsDataRowEqualsTheJsonRecord() {
        JsonNode body = json(get("/api/v1/hotspots?" + EXPORT_Q));
        List<String> lines = get("/api/export.csv?type=hotspots&" + EXPORT_Q).body().lines().toList();
        assertThat(lines).hasSize(1 + body.get("count").asInt());
        String[] names = lines.get(0).split(",");
        for (int row = 0; row < body.get("count").asInt(); row++) {
            JsonNode h = body.get("hotspots").get(row);
            String[] cells = lines.get(1 + row).split(",", -1);
            assertThat(cells).hasSize(names.length);
            for (int i = 0; i < names.length; i++) {
                JsonNode v = h.get(names[i]);
                String as = "row %d column %s".formatted(row, names[i]);
                if (v == null || v.isNull()) {
                    assertThat(cells[i]).as(as).isEmpty();
                } else if (v.isNumber()) {
                    assertThat(new java.math.BigDecimal(cells[i])).as(as).isEqualByComparingTo(v.decimalValue());
                } else if (v.isBoolean()) {
                    assertThat(cells[i]).as(as).isEqualTo(Boolean.toString(v.asBoolean()));
                } else {
                    assertThat(cells[i]).as(as).isEqualTo(v.asString());
                }
            }
        }
    }

    @Test
    @Order(13)
    void fix2_exportHotspotsTruncatedCarriesTheJsonSignalAsHeaders() {
        String q = "bbox=" + KR_BBOX + "&start=2025-03-01&end=2025-03-31&limit=2";
        JsonNode body = json(get("/api/v1/hotspots?" + q));
        assertThat(body.get("truncated").asBoolean()).isTrue();
        HttpResponse<String> csv = get("/api/export.csv?type=hotspots&" + q);
        assertThat(csv.statusCode()).isEqualTo(200);
        assertThat(csv.body().lines().toList()).hasSize(1 + 2);
        assertThat(csv.headers().firstValue("X-Truncated")).hasValue("true");
        assertThat(csv.headers().firstValue("X-Total-Count")).hasValue(Long.toString(body.get("total").asLong()));
        assertThat(csv.headers().firstValue("X-Next-Cursor")).hasValue(body.get("next_cursor").asString());
        // the cursor from the header pages the export like the JSON
        HttpResponse<String> next = get("/api/export.csv?type=hotspots&" + q + "&cursor=" + body.get("next_cursor").asString());
        assertThat(next.statusCode()).isEqualTo(200);
        assertThat(next.body().lines().skip(1).toList()).doesNotContainAnyElementsOf(csv.body().lines().skip(1).toList());
        // not truncated: false, no cursor header
        HttpResponse<String> whole = get("/api/export.csv?type=hotspots&" + EXPORT_Q.replace("limit=50", "limit=10000"));
        assertThat(whole.headers().firstValue("X-Truncated")).hasValue("false");
        assertThat(whole.headers().firstValue("X-Next-Cursor")).isEmpty();
        assertThat(whole.headers().firstValue("X-Total-Count")).hasValue(Long.toString(whole.body().lines().count() - 1));
    }

    @Test
    @Order(14)
    void fix2_exportUnknownTypeIsTheExistingAliasError() {
        for (String type : List.of("type=nope&", "type=HOTSPOTS&", "")) {
            HttpResponse<String> r = get("/api/export.csv?" + type + EXPORT_Q);
            assertThat(r.statusCode()).as(type).isEqualTo(404);
            assertThat(r.headers().firstValue("Content-Type").orElse("")).startsWith("application/problem+json");
            assertThat(json(r).get("code").asString()).isEqualTo("not_found");
        }
    }

    static void copyPoints() {
        Path from = V11.resolve("harmonized_hotspots");
        try (Stream<Path> all = Files.walk(from)) {
            for (Path p : all.toList()) {
                Path to = POINTS.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(to);
                } else {
                    Files.copy(p, to);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        derive(files("reference", 2004, 3), POINTS.resolve("reference/year=2001/month=03"),
                "SELECT * REPLACE (acq_datetime_utc - INTERVAL 3 YEAR AS acq_datetime_utc, (date_local - INTERVAL 3 YEAR)::DATE AS date_local, "
                        + "'Terra' AS satellite, 'MODIS' AS instrument, NULL::FLOAT AS weight_lo, NULL::FLOAT AS weight_hi, "
                        + "'modis_m2v_terra' AS weight_source) FROM src");
        derive(files("reference", 2004, 4), POINTS.resolve("reference/year=2001/month=04"),
                "SELECT * REPLACE (acq_datetime_utc - INTERVAL 3 YEAR AS acq_datetime_utc, (date_local - INTERVAL 3 YEAR)::DATE AS date_local) FROM src "
                        + "UNION ALL SELECT * REPLACE (acq_datetime_utc - INTERVAL 3 YEAR AS acq_datetime_utc, "
                        + "(date_local - INTERVAL 3 YEAR)::DATE AS date_local, frp + 1 AS frp) FROM src");
    }

    static void derive(String srcGlob, Path dir, String select) {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        String target = dir.resolve("part-0.parquet").toString().replace("'", "''");
        try (java.sql.Connection c = java.sql.DriverManager.getConnection("jdbc:duckdb:"); java.sql.Statement s = c.createStatement()) {
            s.execute("CREATE TABLE src AS SELECT * FROM read_parquet('" + srcGlob.replace("'", "''") + "')");
            s.execute("COPY (" + select + ") TO '" + target + "' (FORMAT parquet)");
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}

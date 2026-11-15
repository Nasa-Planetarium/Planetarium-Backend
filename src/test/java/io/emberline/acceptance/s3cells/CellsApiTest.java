package io.emberline.acceptance.s3cells;

import static org.assertj.core.api.Assertions.assertThat;

import io.emberline.support.AppTestBase;
import io.emberline.support.Contract;
import io.emberline.support.Duck;
import io.emberline.support.Fixtures;
import io.emberline.support.IngestCli;
import io.emberline.support.ReleaseFixtures;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
import tools.jackson.databind.JsonNode;

/**
 * subset (owner ruling 1): /cells over the owner fixture {@code core} (real values) and /hotspots 「준비 중」.
 * Expected cells come from the release files: DuckDB over harmonized_cell_month.parquet and the KR boundary of
 * aoi_boundaries.geojson (cell center inside the boundary — the oracle rule, re-done here with plain JTS).
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CellsApiTest extends AppTestBase {

    static final GeometryFactory GF = new GeometryFactory();
    Path root;

    @BeforeAll
    void activate() {
        root = ReleaseFixtures.newRoot();
        assertThat(IngestCli.run(jdbcUrl, root, "stage", "core").exit()).isZero();
        assertThat(IngestCli.run(jdbcUrl, root, "activate", "core", "--expect-sha256", Fixtures.coreManifestSha256()).exit()).isZero();
        awaitRelease("core");
    }

    /** The app picks the switch up by polling (or the l1-refresh event); a cached test context may lag ≤ 10 s. */
    void awaitRelease(String key) {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            JsonNode release = json(get("/api/v1/version")).get("release");
            if (release != null && !release.isNull() && release.get("release_key").asString().equals(key)) {
                return;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        throw new AssertionError("release " + key + " not active in time");
    }

    /** cell_id → row of the fixture for one month whose center is inside the AOI boundary. */
    Map<Long, List<Object>> expectedInside(String aoiId, int year, int month) throws Exception {
        JsonNode fc = JSON.readTree(Files.readString(Duck.core("aoi_boundaries.geojson")));
        Geometry boundary = null;
        for (JsonNode f : fc.get("features")) {
            if (f.get("properties").get("aoi_id").asString().equals(aoiId)) {
                boundary = geometry(f.get("geometry"));
            }
        }
        Map<Long, List<Object>> out = new LinkedHashMap<>();
        for (List<Object> r : Duck.query("SELECT cell_id, cell_lat, cell_lon, value, value_lo, value_hi, raw_modis, raw_viirs, "
                + "value_source, data_source, status FROM read_parquet(?) WHERE year = ? AND month = ? ORDER BY cell_id",
                Duck.core("harmonized_cell_month.parquet"), year, month)) {
            double lat = ((Number) r.get(1)).floatValue();
            double lon = ((Number) r.get(2)).floatValue();
            if (boundary.contains(GF.createPoint(new Coordinate(lon, lat)))) {
                out.put(((Number) r.get(0)).longValue(), r);
            }
        }
        return out;
    }

    static Geometry geometry(JsonNode g) {
        if (g.get("type").asString().equals("Polygon")) {
            return polygon(g.get("coordinates"));
        }
        List<Polygon> parts = new ArrayList<>();
        g.get("coordinates").forEach(p -> parts.add(polygon(p)));
        return GF.createMultiPolygon(parts.toArray(new Polygon[0]));
    }

    static Polygon polygon(JsonNode rings) {
        List<LinearRing> all = new ArrayList<>();
        for (JsonNode ring : rings) {
            List<Coordinate> c = new ArrayList<>();
            ring.forEach(xy -> c.add(new Coordinate(xy.get(0).doubleValue(), xy.get(1).doubleValue())));
            all.add(GF.createLinearRing(c.toArray(new Coordinate[0])));
        }
        return GF.createPolygon(all.get(0), all.subList(1, all.size()).toArray(new LinearRing[0]));
    }

    @Test
    @Order(1)
    void b3_12_krCellsAreTheCellsWhoseCenterIsInsideKr() throws Exception {
        HttpResponse<String> r = get("/api/v1/cells?aoi_id=KR&month=2025-03");
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        assertThat(r.headers().firstValue("Content-Type").orElse("")).startsWith("application/geo+json");
        // F-406: Accept application/json gets the same GeoJSON body as application/json; geo+json stays the default
        HttpResponse<String> asJson = get("/api/v1/cells?aoi_id=KR&month=2025-03", "Accept", "application/json");
        assertThat(asJson.statusCode()).as(asJson.body()).isEqualTo(200);
        assertThat(asJson.headers().firstValue("Content-Type").orElse("")).startsWith("application/json");
        assertThat(asJson.body()).isEqualTo(r.body());
        assertThat(get("/api/v1/cells?aoi_id=KR&month=2025-03&format=json").headers().firstValue("Content-Type").orElse(""))
                .startsWith("application/json");
        assertThat(get("/api/v1/cells?aoi_id=KR&month=2025-03", "Accept", "text/csv").statusCode()).isEqualTo(406);
        assertThat(get("/api/v1/cells?aoi_id=KR&month=2025-03&format=xml").statusCode()).as("R-FMT1").isEqualTo(406);
        assertThat(r.headers().firstValue("X-Method-Version")).hasValue("v1-m2v-2026-11-14");
        JsonNode body = json(r);
        Map<Long, List<Object>> expected = expectedInside("KR", 2025, 3);
        assertThat(expected).isNotEmpty();
        JsonNode props = body.get("properties");
        assertThat(props.get("aoi_id").asString()).isEqualTo("KR");
        assertThat(props.has("bbox")).isFalse();
        assertThat(props.get("year").asInt()).isEqualTo(2025);
        assertThat(props.get("month").asInt()).isEqualTo(3);
        assertThat(props.get("unit").asString()).isEqualTo("VIIRS(S-NPP) 기준 화재 감지량");
        assertThat(props.get("cell_size_deg").asDouble()).isEqualTo(0.1);
        assertThat(props.get("n_cells").asInt()).isEqualTo(expected.size());
        assertThat(props.get("month_status").asString()).isEqualTo("ok");
        assertThat(props.get("zero_rule").asString()).isEqualTo("a cell without a feature is 0 (see month_status for missing)");
        assertThat(props.get("method_version").asString()).isEqualTo("v1-m2v-2026-11-14");
        List<Long> ids = new ArrayList<>();
        for (JsonNode f : body.get("features")) {
            long id = f.get("id").asLong();
            ids.add(id);
            List<Object> row = expected.get(id);
            assertThat(row).as("cell %s expected", id).isNotNull();
            JsonNode p = f.get("properties");
            assertThat(f.get("type").asString()).isEqualTo("Feature");
            assertThat(p.get("cell_id").asLong()).isEqualTo(id);
            assertThat(p.get("value").asDouble()).isEqualTo(((Number) row.get(3)).doubleValue());
            assertThat(p.get("value_lo").asDouble()).isEqualTo(((Number) row.get(4)).doubleValue());
            assertThat(p.get("value_hi").asDouble()).isEqualTo(((Number) row.get(5)).doubleValue());
            assertThat(p.get("raw_modis").asInt()).isEqualTo(((Number) row.get(6)).intValue());
            assertThat(p.get("raw_viirs").asInt()).isEqualTo(((Number) row.get(7)).intValue());
            assertThat(p.get("value_source").asString()).isEqualTo(row.get(8));
            assertThat(p.get("data_source").asString()).isEqualTo(row.get(9));
            assertThat(p.get("status").asString()).isEqualTo(row.get(10));
            assertThat(p.get("cell_lat").asDouble()).isEqualTo(Double.parseDouble(Float.toString(((Number) row.get(1)).floatValue())));
            assertThat(p.has("n_events_viirs")).isTrue();
            assertThat(p.get("n_events_viirs").isNull()).isTrue();
            long southTenths = id / 3600 - 900;
            long westTenths = id % 3600 - 1800;
            JsonNode ring = f.get("geometry").get("coordinates").get(0);
            assertThat(f.get("geometry").get("type").asString()).isEqualTo("Polygon");
            assertThat(ring.size()).isEqualTo(5);
            assertThat(ring.get(0).get(0).asDouble()).isEqualTo(westTenths / 10.0);
            assertThat(ring.get(0).get(1).asDouble()).isEqualTo(southTenths / 10.0);
            assertThat(ring.get(2).get(0).asDouble()).isEqualTo((westTenths + 1) / 10.0);
            assertThat(ring.get(2).get(1).asDouble()).isEqualTo((southTenths + 1) / 10.0);
        }
        assertThat(ids).containsExactlyElementsOf(expected.keySet());

        HttpResponse<String> alias = get("/api/cells?aoi_id=KR&year=2025&month=3");
        assertThat(alias.statusCode()).isEqualTo(200);
        assertThat(alias.headers().firstValue("Content-Type").orElse("")).startsWith("application/geo+json");
        assertThat(alias.body()).isEqualTo(r.body());
        assertThat(alias.headers().firstValue("Link").orElse("")).contains("/api/v1/cells?").contains("month=2025-03");
    }

    @Test
    @Order(2)
    void cellOfTheContractExampleHasItsCornerRing() {
        // contract CellCollection example: cell 4549889 → corner (36.3, 128.9)
        JsonNode body = json(get("/api/v1/cells?bbox=128.85,36.25,129.05,36.45&month=2025-03"));
        JsonNode cell = null;
        for (JsonNode f : body.get("features")) {
            if (f.get("id").asLong() == 4549889L) {
                cell = f;
            }
        }
        assertThat(cell).isNotNull();
        assertThat(cell.get("geometry").get("coordinates").toString())
                .isEqualTo("[[[128.9,36.3],[129.0,36.3],[129.0,36.4],[128.9,36.4],[128.9,36.3]]]");
        assertThat(body.get("properties").get("bbox").toString()).isEqualTo("[128.85,36.25,129.05,36.45]");
        assertThat(body.get("properties").has("aoi_id")).isFalse();
    }

    @Test
    @Order(3)
    void bboxSelectsCellCentersInsideTheBox() {
        JsonNode body = json(get("/api/v1/cells?bbox=126,34,130,39&month=2025-03"));
        long expected = ((Number) Duck.query("SELECT count(*) FROM read_parquet(?) WHERE year = 2025 AND month = 3 "
                + "AND cell_lat BETWEEN 34 AND 39 AND cell_lon BETWEEN 126 AND 130", Duck.core("harmonized_cell_month.parquet"))
                .get(0).get(0)).longValue();
        assertThat(body.get("features").size()).isEqualTo((int) expected);
        assertThat(body.get("properties").get("n_cells").asInt()).isEqualTo((int) expected);
    }

    @Test
    @Order(4)
    void b3_13_worldContinentAndHugeBboxAre40103() {
        for (String q : List.of("aoi_id=WORLD", "aoi_id=CONT_Asia", "bbox=100,20,130,50")) {
            HttpResponse<String> r = get("/api/v1/cells?" + q + "&month=2025-03");
            assertThat(r.statusCode()).as(q).isEqualTo(400);
            assertThat(r.headers().firstValue("Content-Type").orElse("")).startsWith("application/problem+json");
            assertThat(json(r).get("code").asString()).isEqualTo("range_too_large");
            assertThat(json(r).get("error_no").asInt()).isEqualTo(40103);
            assertThat(json(r).get("param").asString()).isEqualTo(q.substring(0, q.indexOf('=')));
        }
        // 100 x 100 cells = 10,000 is still allowed; 101 x 100 is not
        assertThat(get("/api/v1/cells?bbox=120,30,130,40&month=2025-03").statusCode()).isEqualTo(200);
        assertThat(get("/api/v1/cells?bbox=120,30,130.1,40&month=2025-03").statusCode()).isEqualTo(400);
    }

    @Test
    @Order(5)
    void missingMonthIsNullNotZero() {
        HttpResponse<String> r = get("/api/v1/cells?aoi_id=KR&month=2001-06");
        assertThat(r.statusCode()).isEqualTo(200);
        JsonNode body = json(r);
        assertThat(body.get("properties").get("month_status").asString()).isEqualTo("missing");
        assertThat(body.get("features").size()).isPositive();
        for (JsonNode f : body.get("features")) {
            JsonNode p = f.get("properties");
            assertThat(p.get("value").isNull()).isTrue();
            assertThat(p.get("value_lo").isNull()).isTrue();
            assertThat(p.get("status").asString()).isEqualTo("missing");
            assertThat(p.get("interval_unavailable").asBoolean()).isTrue();
            assertThat(p.get("interval_out_of_range").asBoolean()).isFalse();
        }
    }

    @Test
    @Order(6)
    void outOfIntervalAndNoIntervalRowsAreFlaggedWithFileValues() {
        List<Object> out = Duck.query("SELECT cell_id, year, month, value, value_lo, value_hi FROM read_parquet(?) "
                + "WHERE value > value_hi OR value < value_lo ORDER BY year, month, cell_id LIMIT 1",
                Duck.core("harmonized_cell_month.parquet")).get(0);
        JsonNode f = feature(out);
        assertThat(f.get("properties").get("value").asDouble()).isEqualTo(((Number) out.get(3)).doubleValue());
        assertThat(f.get("properties").get("value_lo").asDouble()).isEqualTo(((Number) out.get(4)).doubleValue());
        assertThat(f.get("properties").get("value_hi").asDouble()).isEqualTo(((Number) out.get(5)).doubleValue());
        assertThat(f.get("properties").get("interval_out_of_range").asBoolean()).isTrue();
        assertThat(f.get("properties").get("interval_unavailable").asBoolean()).isFalse();

        List<Object> terra = Duck.query("SELECT cell_id, year, month, value FROM read_parquet(?) "
                + "WHERE value IS NOT NULL AND value_lo IS NULL ORDER BY year, month, cell_id LIMIT 1",
                Duck.core("harmonized_cell_month.parquet")).get(0);
        JsonNode t = feature(terra);
        assertThat(t.get("properties").get("value").asDouble()).isEqualTo(((Number) terra.get(3)).doubleValue());
        assertThat(t.get("properties").get("value_lo").isNull()).isTrue();
        assertThat(t.get("properties").get("interval_unavailable").asBoolean()).isTrue();
        assertThat(t.get("properties").get("interval_out_of_range").asBoolean()).isFalse();
    }

    JsonNode feature(List<Object> row) {
        long id = ((Number) row.get(0)).longValue();
        double lat = (id / 3600 - 900) / 10.0 + 0.05;
        double lon = (id % 3600 - 1800) / 10.0 + 0.05;
        String bbox = String.format(java.util.Locale.ROOT, "%.2f,%.2f,%.2f,%.2f", lon - 0.04, lat - 0.04, lon + 0.04, lat + 0.04);
        String month = String.format(java.util.Locale.ROOT, "%04d-%02d", ((Number) row.get(1)).intValue(), ((Number) row.get(2)).intValue());
        JsonNode body = json(get("/api/v1/cells?bbox=" + bbox + "&month=" + month));
        assertThat(body.get("features").size()).isEqualTo(1);
        assertThat(body.get("features").get(0).get("id").asLong()).isEqualTo(id);
        return body.get("features").get(0);
    }

    @Test
    @Order(9)
    @SuppressWarnings("unchecked")
    void cellPropertiesAreTheContractSchemaIncludingTheIntervalFlags() throws java.io.IOException {
        Map<String, Object> schema = Contract.at("components", "schemas", "CellProperties");
        java.util.Set<String> declared = new java.util.TreeSet<>(((Map<String, Object>) schema.get("properties")).keySet());
        for (String part : List.of("Interval", "EventColumns")) {
            declared.addAll(((Map<String, Object>) Contract.at("components", "schemas", part).get("properties")).keySet());
        }
        // Spec 272aedb dropped the two read-path flags from CellProperties; the backend still adds them.
        List<String> flags = List.of("interval_out_of_range", "interval_unavailable");
        declared.addAll(flags);
        List<String> required = new ArrayList<>((List<String>) schema.get("required"));
        required.addAll(flags);
        // around a fixture cell of each case: value outside its interval, no interval (Terra), missing month
        java.util.Set<String> flagged = new java.util.TreeSet<>();
        for (String where : List.of("value > value_hi OR value < value_lo", "value IS NOT NULL AND value_lo IS NULL", "value IS NULL")) {
            List<Object> row = Duck.query("SELECT cell_id, year, month FROM read_parquet(?) WHERE " + where
                    + " ORDER BY year, month, cell_id LIMIT 1", Duck.core("harmonized_cell_month.parquet")).get(0);
            long id = ((Number) row.get(0)).longValue();
            double lat = (id / 3600 - 900) / 10.0 + 0.05;
            double lon = (id % 3600 - 1800) / 10.0 + 0.05;
            String q = String.format(java.util.Locale.ROOT, "bbox=%.2f,%.2f,%.2f,%.2f&month=%04d-%02d", lon - 2, lat - 2, lon + 2, lat + 2,
                    ((Number) row.get(1)).intValue(), ((Number) row.get(2)).intValue());
            for (JsonNode f : json(get("/api/v1/cells?" + q)).get("features")) {
                JsonNode p = f.get("properties");
                java.util.Set<String> served = new java.util.TreeSet<>();
                p.properties().forEach(e -> served.add(e.getKey()));
                assertThat(declared).as(q + " cell " + p.get("cell_id")).containsAll(served);
                assertThat(served).containsAll(required);
                for (String flag : List.of("interval_out_of_range", "interval_unavailable")) {
                    assertThat(p.get(flag).isBoolean()).isTrue();
                    if (p.get(flag).asBoolean()) {
                        flagged.add(flag);
                    }
                }
            }
        }
        assertThat(flagged).as("both flags occur in the fixture").containsExactly("interval_out_of_range", "interval_unavailable");
    }

    @Test
    @Order(7)
    void parametersAreValidated() {
        Map<String, String> cases = Map.of(
                "/api/v1/cells?month=2025-03", "aoi_id",
                "/api/v1/cells?aoi_id=KR&bbox=126,34,130,39&month=2025-03", "bbox",
                "/api/v1/cells?aoi_id=KR", "month",
                "/api/v1/cells?aoi_id=KR&month=2025-13", "month",
                "/api/v1/cells?bbox=130,34,126,39&month=2025-03", "bbox",
                "/api/v1/cells?bbox=1,2,3&month=2025-03", "bbox",
                "/api/v1/cells?aoi_id=KR%27--&month=2025-03", "aoi_id");
        cases.forEach((url, param) -> {
            HttpResponse<String> r = get(url);
            assertThat(r.statusCode()).as(url).isEqualTo(400);
            assertThat(json(r).get("code").asString()).as(url).isEqualTo("invalid_param");
            assertThat(json(r).get("param").asString()).as(url).isEqualTo(param);
        });
        assertThat(json(get("/api/v1/cells?aoi_id=NOPE&month=2025-03")).get("code").asString()).isEqualTo("unknown_aoi");
        JsonNode outOfRange = json(get("/api/v1/cells?aoi_id=KR&month=2099-01"));
        assertThat(outOfRange.get("properties").get("month_status").asString()).isEqualTo("out_of_range");
        assertThat(outOfRange.get("features").size()).isZero();
    }

    @Test
    @Order(8)
    void b3_15_hotspotsArePreparingWhileOtherRoutesServe() {
        for (String path : List.of("/api/v1/hotspots?aoi_id=KR&start=2025-03-01&end=2025-03-31",
                "/api/hotspots?aoi_id=KR&start=2025-03-01&end=2025-03-31")) {
            HttpResponse<String> r = get(path);
            assertThat(r.statusCode()).as(path).isEqualTo(503);
            assertThat(r.headers().firstValue("Content-Type").orElse("")).startsWith("application/problem+json");
            assertThat(r.headers().firstValue("Retry-After")).isPresent();
            assertThat(json(r).get("code").asString()).isEqualTo("data_not_loaded");
            assertThat(json(r).get("error_no").asInt()).isEqualTo(50501);
            assertThat(json(r).get("detail").asString()).contains("준비 중");
        }
        assertThat(get("/api/v1/aois/KR").statusCode()).isEqualTo(200);
        assertThat(get("/api/v1/cells?aoi_id=SIDO_47&month=2025-03").statusCode()).isEqualTo(200);
    }

    @Test
    @Order(10)
    void fix2_releaseWithoutProjectionColumnsLoadsAndCellsOmitBoth() {
        // v1 fixture core: neither the cell file nor aoi_month has value_projected / as_of_date, yet 2026-11 is partial
        for (String file : List.of("harmonized_cell_month.parquet", "aoi_month.parquet")) {
            List<String> columns = Duck.query("SELECT column_name FROM (DESCRIBE SELECT * FROM read_parquet(?))", Duck.core(file))
                    .stream().map(r -> (String) r.get(0)).toList();
            assertThat(columns).as(file).doesNotContain("value_projected", "as_of_date");
        }
        HttpResponse<String> r = get("/api/v1/cells?aoi_id=KR&month=2026-11");
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        JsonNode fc = json(r);
        assertThat(fc.get("properties").get("month_status").asString()).isEqualTo("partial");
        assertThat(fc.get("features").size()).isPositive();
        for (JsonNode f : fc.get("features")) {
            assertThat(f.get("properties").get("status").asString()).isEqualTo("partial");
            assertThat(f.get("properties").has("value_projected")).isFalse();
            assertThat(f.get("properties").has("as_of_date")).isFalse();
        }
        assertThat(r.body()).doesNotContain("value_projected").doesNotContain("as_of_date");
    }
}

package io.emberline.b1;

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
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * B1 read side over real HTTP on the owner fixture {@code core} (real values). Expected values come from the release
 * files (DuckDB over the parquet / geojson / meta) or from contract examples that quote real values.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestPropertySource(properties = "emberline.release.poll-interval=1s")
class B1ApiTest extends AppTestBase {

    static final String META_MV = "v1-m2v-2026-11-14";
    static final String B_MV = "v1-m2v-2026-11-14-b";
    Path root;
    String etagBeforeSwitch;

    @BeforeAll
    void releases() {
        root = ReleaseFixtures.newRoot();
        Path b = ReleaseFixtures.copyCore(root, "core-b");
        ReleaseFixtures.rewriteText(b, "m2v_meta.json", "\"method_version\": \"" + META_MV + "\"", "\"method_version\": \"" + B_MV + "\"");
        ReleaseFixtures.rewrite(b, "harmonized_cell_month.parquet", "SELECT * REPLACE ('" + B_MV + "' AS method_version) FROM src");
    }

    JsonNode waitForVersion(Predicate<JsonNode> ok) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        JsonNode body = null;
        while (System.nanoTime() < deadline) {
            body = json(get("/api/v1/version"));
            if (ok.test(body)) {
                return body;
            }
            Thread.sleep(200);
        }
        return body;
    }

    @Test
    @Order(1)
    void b1_07_dataRoutesAre503BeforeActivation() {
        HttpResponse<String> r = get("/api/v1/aois");
        assertThat(r.statusCode()).isEqualTo(503);
        assertThat(r.headers().firstValue("Content-Type").orElse("")).startsWith("application/problem+json");
        assertThat(r.headers().firstValue("Retry-After")).hasValue("60");
        assertThat(json(r).get("code").asString()).isEqualTo("data_not_loaded");
        assertThat(json(r).get("error_no").asInt()).isEqualTo(50501);
        assertThat(get("/api/v1/version").statusCode()).isEqualTo(200);
    }

    @Test
    @Order(2)
    void b1_01_stageAndActivateShowInVersion() throws Exception {
        IngestCli.Result staged = IngestCli.run(jdbcUrl, root, "stage", "core");
        assertThat(staged.exit()).as(staged.json().toString()).isZero();
        assertThat(staged.state()).isEqualTo("LOADED");
        IngestCli.Result active = IngestCli.run(jdbcUrl, root, "activate", "core", "--expect-sha256", Fixtures.coreManifestSha256());
        assertThat(active.exit()).as(active.json().toString()).isZero();
        assertThat(active.state()).isEqualTo("ACTIVE");
        String expectedDataVersion = "core#" + Fixtures.coreManifestSha256().substring(0, 12);
        JsonNode v = waitForVersion(b -> !b.get("release").isNull());
        assertThat(v.get("release").get("release_key").asString()).isEqualTo("core");
        assertThat(v.get("release").get("data_version").asString()).isEqualTo(expectedDataVersion);
        assertThat(v.get("release").get("method_version").asString()).isEqualTo(META_MV);
        assertThat(v.get("data_version").asString()).isEqualTo(expectedDataVersion);
    }

    @Test
    @Order(3)
    void b1_08_countryListOrderFieldsAndCursor() {
        List<String> expected = Duck.query("SELECT aoi_id FROM read_parquet(?) WHERE level IN ('country', 'kr') AND NOT hidden",
                Duck.core("aoi_list.parquet")).stream().map(r -> (String) r.get(0)).sorted().toList();
        JsonNode page = json(get("/api/v1/aois?level=country"));
        assertThat(page.get("method_version").asString()).isEqualTo(META_MV);
        assertThat(page.get("unit").asString()).isEqualTo("VIIRS(S-NPP) 기준 화재 감지량");
        assertThat(page.get("count").asInt()).isEqualTo(100);
        assertThat(ids(page)).containsExactlyElementsOf(expected.subList(0, 100));
        assertThat(page.get("next_cursor").isString()).isTrue();
        JsonNode all = json(get("/api/v1/aois?level=country&limit=1000"));
        assertThat(ids(all)).containsExactlyElementsOf(expected);
        assertThat(all.get("next_cursor").isNull()).isTrue();
        JsonNode kr = find(all, "KR");
        assertThat(kr.get("name_ko").asString()).isEqualTo("대한민국");
        assertThat(kr.get("name_en").isNull()).isTrue();
        assertThat(kr.get("level").asString()).isEqualTo("country");
        assertThat(kr.get("parent_id").asString()).isEqualTo("CONT_Asia");
        assertThat(kr.get("n_cells").asInt()).isEqualTo(1123);
        assertThat(kr.get("hidden").asBoolean()).isFalse();
        assertThat(kr.get("has_data").asBoolean()).isTrue();
        assertThat(kr.get("first_month").asString()).isEqualTo("2000-11");
        assertThat(kr.get("last_month").asString()).isEqualTo("2026-11");
        assertThat(doubles(kr.get("bbox"))).containsExactly(124.614, 33.198, 130.921, 38.624);
        assertThat(fieldNames(kr)).containsExactlyInAnyOrder("aoi_id", "name_ko", "name_en", "level", "parent_id",
                "n_cells", "hidden", "has_data", "first_month", "last_month", "bbox");
        long hiddenCountries = Duck.query("SELECT count(*) FROM read_parquet(?) WHERE level = 'country' AND hidden",
                Duck.core("aoi_list.parquet")).get(0).get(0) instanceof Number n ? n.longValue() : -1;
        JsonNode withHidden = json(get("/api/v1/aois?level=country&include_hidden=true&limit=1000"));
        assertThat(withHidden.get("count").asInt()).isEqualTo(expected.size() + (int) hiddenCountries);
        assertThat(json(get("/api/v1/aois?q=" + "x".repeat(101))).get("param").asString()).isEqualTo("q");
        assertThat(json(get("/api/v1/aois?level=planet")).get("param").asString()).isEqualTo("level");
        JsonNode noData = find(all, "ADM0_ABW");
        assertThat(noData.get("has_data").asBoolean()).isFalse();
        assertThat(noData.get("first_month").isNull()).isTrue();
    }

    @Test
    @Order(4)
    void b1_09_snakeCaseParentAndLiteralQ() {
        JsonNode sido = json(get("/api/v1/aois?parent_id=KR&level=kr_sido"));
        assertThat(sido.get("count").asInt()).isEqualTo(17);
        JsonNode seoul = find(sido, "SIDO_11");
        assertThat(seoul.get("name_ko").asString()).isEqualTo("서울특별시");
        assertThat(seoul.get("level").asString()).isEqualTo("kr_sido");
        assertThat(seoul.get("parent_id").asString()).isEqualTo("KR");
        assertThat(seoul.get("n_cells").asInt()).isEqualTo(15);
        assertThat(doubles(seoul.get("bbox"))).containsExactly(126.765, 37.43, 127.184, 37.701);
        assertThat(ids(sido)).allMatch(id -> id.startsWith("SIDO_"));

        assertThat(json(get("/api/v1/aois?q=%25&limit=1000")).get("count").asInt()).isZero();
        JsonNode underscore = json(get("/api/v1/aois?q=_&limit=1000"));
        assertThat(underscore.get("count").asInt()).isPositive();
        for (JsonNode a : underscore.get("aois")) {
            String all = a.get("aoi_id").asString() + "|" + a.get("name_ko").asString();
            assertThat(all).contains("_");
        }
        assertThat(ids(underscore)).doesNotContain("KR", "WORLD");
        assertThat(ids(json(get("/api/v1/aois?q=%EC%84%9C%EC%9A%B8")))).contains("SIDO_11");
    }

    @Test
    @Order(5)
    void b1_10_limitBoundsAndCursorWalk() {
        HttpResponse<String> tooMany = get("/api/v1/aois?limit=1001");
        assertThat(tooMany.statusCode()).isEqualTo(400);
        assertThat(json(tooMany).get("code").asString()).isEqualTo("invalid_param");
        assertThat(json(tooMany).get("param").asString()).isEqualTo("limit");
        assertThat(json(get("/api/v1/aois?limit=0")).get("param").asString()).isEqualTo("limit");
        assertThat(json(get("/api/v1/aois?limit=abc")).get("param").asString()).isEqualTo("limit");

        List<String> all = ids(json(get("/api/v1/aois?level=country&limit=1000")));
        List<String> walked = new ArrayList<>();
        String cursor = null;
        for (int i = 0; i < 1000; i++) {
            JsonNode page = json(get("/api/v1/aois?level=country&limit=2" + (cursor == null ? "" : "&cursor=" + cursor)));
            assertThat(page.get("count").asInt()).isLessThanOrEqualTo(2);
            walked.addAll(ids(page));
            if (page.get("next_cursor").isNull()) {
                break;
            }
            cursor = page.get("next_cursor").asString();
        }
        assertThat(walked).containsExactlyElementsOf(all);
        assertThat(new HashSet<>(walked)).hasSize(walked.size());
        assertThat(json(get("/api/v1/aois?cursor=bm9wZQ")).get("param").asString()).isEqualTo("cursor");
    }

    @Test
    @Order(6)
    void b1_11_injectionIs400AndUnknownIs404() {
        HttpResponse<String> evil = get("/api/v1/aois/KR'%20OR%201=1--");
        assertThat(evil.statusCode()).isEqualTo(400);
        assertThat(json(evil).get("code").asString()).isEqualTo("invalid_param");
        assertThat(json(evil).get("param").asString()).isEqualTo("aoi_id");
        HttpResponse<String> unknown = get("/api/v1/aois/ZZZ");
        assertThat(unknown.statusCode()).isEqualTo(404);
        assertThat(json(unknown).get("code").asString()).isEqualTo("unknown_aoi");
        assertThat(json(unknown).get("error_no").asInt()).isEqualTo(20401);
        assertThat(json(unknown).get("param").asString()).isEqualTo("aoi_id");
        assertThat(json(get("/api/v1/aois?parent_id=NOPE")).get("code").asString()).isEqualTo("unknown_aoi");
        JsonNode kr = json(get("/api/v1/aois/KR"));
        assertThat(kr.get("method_version").asString()).isEqualTo(META_MV);
        assertThat(kr.get("n_cells").asInt()).isEqualTo(1123);
    }

    @Test
    @Order(7)
    void b1_12_boundaryIsGeoJsonAsInTheRelease() throws Exception {
        HttpResponse<String> r = get("/api/v1/aois/KR/boundary");
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.headers().firstValue("Content-Type").orElse("")).startsWith("application/geo+json");
        JsonNode body = json(r);
        assertThat(body.get("type").asString()).isEqualTo("Feature");
        assertThat(body.get("properties").get("aoi_id").asString()).isEqualTo("KR");
        assertThat(body.get("properties").get("method_version").asString()).isEqualTo(META_MV);
        JsonNode fixture = null;
        for (JsonNode f : JSON.readTree(Files.readString(Duck.core("aoi_boundaries.geojson"))).get("features")) {
            if (f.get("properties").get("aoi_id").asString().equals("KR")) {
                fixture = f.get("geometry");
            }
        }
        assertThat(fixture).isNotNull();
        assertThat(body.get("geometry")).isEqualTo(fixture);
        JsonNode world = json(get("/api/v1/aois/WORLD/boundary"));
        assertThat(world.get("geometry").isNull()).isTrue();
    }

    @Test
    @Order(8)
    void b1_13_currentMethodIsTheMetaFile() throws Exception {
        HttpResponse<String> r = get("/api/v1/methods/current");
        assertThat(r.statusCode()).isEqualTo(200);
        JsonNode body = json(r);
        assertThat(body.get("method_version").asString()).isEqualTo(META_MV);
        assertThat(r.headers().firstValue("X-Method-Version")).hasValue(body.get("method_version").asString());
        assertThat(body.get("meta")).isEqualTo(JSON.readTree(Files.readString(Duck.core("m2v_meta.json"))));
        assertThat(body.get("unit_code").asString()).isEqualTo("snpp_equiv_hotspots");
        assertThat(body.get("data_version").asString()).isEqualTo("core#" + Fixtures.coreManifestSha256().substring(0, 12));
        assertThat(body.get("ingest").get("aliases_used").toString()).contains("parent_aoi->parent_id", "kr->country");
        assertThat(json(get("/api/v1/methods/" + META_MV)).get("method_version").asString()).isEqualTo(META_MV);
        assertThat(get("/api/v1/methods/v0-other").statusCode()).isEqualTo(404);
    }

    @Test
    @Order(9)
    void b1_14_satelliteStatusIsMonthLevelFromTheFiles() {
        HttpResponse<String> r = get("/api/v1/satellite-status?start=2025-03-01&end=2025-03-31");
        assertThat(r.statusCode()).isEqualTo(200);
        JsonNode body = json(r);
        assertThat(body.get("granularity").asString()).isEqualTo("month");
        assertThat(body.get("day_level").asString()).isEqualTo("not_provided");
        assertThat(body.has("days")).isFalse();
        assertThat(body.get("start").asString()).isEqualTo("2025-03-01");
        assertThat(body.get("end").asString()).isEqualTo("2025-03-31");
        List<List<Object>> world = Duck.query("SELECT value_source, data_source, status FROM read_parquet(?) WHERE aoi_id = 'WORLD' AND year = 2025 AND month = 3",
                Duck.core("aoi_month.parquet"));
        JsonNode march = body.get("months").get(0);
        assertThat(body.get("months")).hasSize(1);
        assertThat(march.get("year_month").asString()).isEqualTo("2025-03");
        assertThat(march.get("value_source").asString()).isEqualTo(world.get(0).get(0));
        assertThat(march.get("data_source").asString()).isEqualTo(world.get(0).get(1));
        assertThat(march.get("status").asString()).isEqualTo(world.get(0).get(2));
        assertThat(body.get("satellites").get(0).get("satellite").asString()).isEqualTo("N");
        assertThat(body.get("method_version").asString()).isEqualTo(META_MV);

        JsonNode missing = json(get("/api/v1/satellite-status?start=2001-06-01&end=2001-06-30"));
        assertThat(missing.get("months").get(0).get("status").asString()).isEqualTo("missing");
        JsonNode outside = json(get("/api/v1/satellite-status?start=2000-01-01&end=2000-01-31"));
        assertThat(outside.get("months").get(0).get("status").asString()).isEqualTo("out_of_range");
        assertThat(json(get("/api/v1/satellite-status?start=2025-04-01&end=2025-03-01")).get("param").asString()).isEqualTo("start");
        assertThat(json(get("/api/v1/satellite-status?start=2025-02-30")).get("param").asString()).isEqualTo("start");
        assertThat(get("/api/v1/aois").statusCode()).isEqualTo(200);
    }

    @Test
    @Order(10)
    void b1_15_etagRevalidation() {
        HttpResponse<String> first = get("/api/v1/aois?level=country");
        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(first.headers().firstValue("Cache-Control")).hasValue("public, max-age=0, must-revalidate");
        assertThat(first.headers().firstValue("Vary")).hasValue("Accept, Accept-Language");
        assertThat(first.headers().firstValue("X-Data-Version")).isPresent();
        String etag = first.headers().firstValue("ETag").orElseThrow();
        HttpResponse<String> again = get("/api/v1/aois?level=country", "If-None-Match", etag);
        assertThat(again.statusCode()).isEqualTo(304);
        assertThat(again.body()).isEmpty();
        assertThat(get("/api/v1/aois?level=kr_sido", "If-None-Match", etag).statusCode()).isEqualTo(200);
        etagBeforeSwitch = etag;
    }

    @Test
    @Order(11)
    void b1_18_aoiAliasForwardsWithCanonicalLink() {
        HttpResponse<String> alias = get("/api/aoi?level=country");
        HttpResponse<String> canonical = get("/api/v1/aois?level=country");
        assertThat(alias.statusCode()).isEqualTo(200);
        assertThat(json(alias)).isEqualTo(json(canonical));
        assertThat(alias.headers().firstValue("Link")).hasValue("</api/v1/aois?level=country>; rel=\"canonical\"");
    }

    @Test
    @Order(12)
    void b1_19_methodAndStatusAliases() {
        assertThat(json(get("/api/method"))).isEqualTo(json(get("/api/v1/methods/current")));
        HttpResponse<String> status = get("/api/status?start=2025-03-01&end=2025-03-31");
        assertThat(status.statusCode()).isEqualTo(200);
        assertThat(json(status)).isEqualTo(json(get("/api/v1/satellite-status?start=2025-03-01&end=2025-03-31")));
        assertThat(status.headers().firstValue("Link")).hasValue("</api/v1/satellite-status?start=2025-03-01&end=2025-03-31>; rel=\"canonical\"");
        assertThat(get("/api/unknown-alias").statusCode()).isEqualTo(404);
    }

    @Test
    @Order(13)
    @SuppressWarnings("unchecked")
    void b1_20_openapiListsImplementedContractPathsOnly() {
        JsonNode doc = json(get("/api/v1/openapi.json"));
        Set<String> paths = new TreeSet<>();
        doc.get("paths").properties().forEach(e -> paths.add(e.getKey()));
        assertThat(paths).contains("/api/v1/aois", "/api/v1/aois/{aoiId}", "/api/v1/aois/{aoiId}/boundary",
                "/api/v1/methods/current", "/api/v1/methods/{methodVersion}", "/api/v1/satellite-status", "/api/v1/version");
        Map<String, Object> contractPaths = (Map<String, Object>) Contract.openapi().get("paths");
        for (String p : paths) {
            String contractPath = p.substring("/api/v1".length());
            assertThat(contractPaths).as("contract has %s", contractPath).containsKey(contractPath);
            Set<String> served = new TreeSet<>();
            JsonNode params = doc.get("paths").get(p).get("get").get("parameters");
            if (params != null) {
                params.forEach(n -> served.add(n.get("in").asString() + ":" + n.get("name").asString()));
            }
            assertThat(served).as("parameters of %s", p).isEqualTo(contractParams(contractPath));
        }
        assertThat(paths).noneMatch(p -> !p.startsWith("/api/v1/"));
    }

    @SuppressWarnings("unchecked")
    static Set<String> contractParams(String path) {
        Map<String, Object> op = (Map<String, Object>) ((Map<String, Object>) ((Map<String, Object>) Contract.openapi().get("paths")).get(path)).get("get");
        Set<String> out = new TreeSet<>();
        List<Object> params = (List<Object>) op.getOrDefault("parameters", List.of());
        for (Object o : params) {
            Map<String, Object> p = (Map<String, Object>) o;
            if (p.containsKey("$ref")) {
                String name = ((String) p.get("$ref")).substring("#/components/parameters/".length());
                p = (Map<String, Object>) Contract.at("components", "parameters").get(name);
            }
            out.add(p.get("in") + ":" + p.get("name"));
        }
        return out;
    }

    @Test
    @Order(14)
    void b1_16_switchWhileRunning() throws Exception {
        assertThat(IngestCli.run(jdbcUrl, root, "stage", "core-b").exit()).isZero();
        String bSha = Fixtures.sha256(root.resolve("core-b/SHA256SUMS"));
        assertThat(IngestCli.run(jdbcUrl, root, "activate", "core-b", "--expect-sha256", bSha).exit()).isZero();
        JsonNode v = waitForVersion(b -> b.get("release").get("release_key").asString().equals("core-b"));
        assertThat(v.get("release").get("release_key").asString()).isEqualTo("core-b");
        assertThat(v.get("release").get("method_version").asString()).isEqualTo(B_MV);
        HttpResponse<String> list = get("/api/v1/aois?level=country", "If-None-Match", etagBeforeSwitch);
        assertThat(list.statusCode()).isEqualTo(200);
        assertThat(list.headers().firstValue("X-Method-Version")).hasValue(B_MV);
    }

    @Test
    @Order(15)
    void b1_17_rollbackToThePreviousRelease() throws Exception {
        IngestCli.Result back = IngestCli.run(jdbcUrl, root, "activate", "core", "--expect-sha256", Fixtures.coreManifestSha256());
        assertThat(back.exit()).as(back.json().toString()).isZero();
        JsonNode v = waitForVersion(b -> b.get("release").get("release_key").asString().equals("core"));
        assertThat(v.get("release").get("method_version").asString()).isEqualTo(META_MV);
        assertThat(v.get("release").get("data_version").asString()).isEqualTo("core#" + Fixtures.coreManifestSha256().substring(0, 12));
        assertThat(get("/api/v1/aois?level=country").headers().firstValue("X-Method-Version")).hasValue(META_MV);
        assertThat(IngestCli.run(jdbcUrl, root, "show", "core-b").state()).isEqualTo("RETIRED");
    }

    static List<String> ids(JsonNode page) {
        List<String> out = new ArrayList<>();
        page.get("aois").forEach(a -> out.add(a.get("aoi_id").asString()));
        return out;
    }

    static JsonNode find(JsonNode page, String id) {
        for (JsonNode a : page.get("aois")) {
            if (a.get("aoi_id").asString().equals(id)) {
                return a;
            }
        }
        throw new AssertionError(id + " not in page");
    }

    static List<Double> doubles(JsonNode array) {
        List<Double> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asDouble()));
        return out;
    }

    static Set<String> fieldNames(JsonNode node) {
        Map<String, Boolean> names = new LinkedHashMap<>();
        node.properties().forEach(e -> names.put(e.getKey(), true));
        return names.keySet();
    }
}

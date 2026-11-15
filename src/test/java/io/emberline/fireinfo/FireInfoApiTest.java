package io.emberline.fireinfo;

import static org.assertj.core.api.Assertions.assertThat;

import io.emberline.fireinfo.FireInfoFixture.Ev;
import io.emberline.fireinfo.FireInfoFixture.Sgg;
import io.emberline.fireinfo.application.port.in.FireEventDetailResponse;
import io.emberline.fireinfo.application.port.in.FireEventListResponse;
import io.emberline.fireinfo.application.port.in.FireRegimeResponse;
import io.emberline.fireinfo.application.port.in.FwiResponse;
import io.emberline.fireinfo.application.port.in.SpreadNowcastResponse;
import io.emberline.platform.flyway.MultiSchemaFlywayRunner;
import io.emberline.shared.cache.LayerCache;
import io.emberline.shared.cache.LayerRoute;
import io.emberline.shared.release.ActiveReleaseSource;
import io.emberline.shared.release.ReleaseRef;
import io.emberline.support.Duck;
import io.emberline.support.EmbeddedPg;
import io.emberline.support.Fixtures;
import io.emberline.support.IngestCli;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * rows L2-1 … L2-11 over real HTTP: release {@code v12layers} (core-v11 + the five fire-info files,
 * aoi_list and aoi_boundaries of owner fixture core-v12-layers) staged and activated; {@code v12layers-nocol} staged only.
 * Expected values come from the fixture parquet (DuckDB) and plain Java in this class, never from the code under test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"management.server.port=0", "emberline.ratelimit.enabled=false", "emberline.release.poll-interval=1s",
                "emberline.layer-cache.env=test", "emberline.layer-cache.retry-after=200ms"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FireInfoApiTest {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    static final Path ROOT = FireInfoFixture.newRoot();
    static final LayerRedis REDIS = new LayerRedis().start();
    static final String LAYER = "public, max-age=0, s-maxage=3600, must-revalidate";
    /** Contract listFireEvents x-csv-columns = FireEvent fields in order. */
    static final List<String> EVENT_COLUMNS = List.of("event_id", "region", "start_date", "end_date", "duration_days", "n_det",
            "sum_weight", "frp_sum", "frp_max", "centroid_lat", "centroid_lon", "lat_min", "lat_max", "lon_min", "lon_max",
            "burned_area_km2", "burn_confirmed", "instrument", "n_viirs", "n_modis", "country", "adm1_code", "sido_nm", "sgg_cd",
            "sgg_nm", "sgg_all");
    static final List<String> FWI_DAY_COLUMNS = List.of("date", "fwi_mean", "fwi_max", "fwi_class", "fwi_pct", "fwi_rel_class",
            "isi_mean", "bui_mean", "ffmc_mean", "dc_mean", "n_cells");
    static final String FWI_DAY_SQL = "SELECT CAST(date AS VARCHAR), fwi_mean, fwi_max, fwi_class, fwi_pct, fwi_rel_class, isi_mean, "
            + "bui_mean, ffmc_mean, dc_mean, CAST(n_cells AS BIGINT) FROM read_parquet(?) WHERE aoi_id = ? ";
    static final List<String> REGIME_COLUMNS = List.of("year", "month", "fire_regime", "W", "W_large", "share_large", "n_events",
            "n_large_events", "top_event_id", "top_event_w");
    static final String REGIME_SQL = "SELECT CAST(year AS BIGINT), CAST(month AS BIGINT), fire_regime, \"W\", \"W_large\", share_large, "
            + "CAST(n_events AS BIGINT), CAST(n_large_events AS BIGINT), top_event_id, top_event_w FROM read_parquet(?) WHERE aoi_id = ? ";
    static final String KR_BOX = "124.5,33,132,38.7";
    static final String EUROPE_BOX = "0,40,10,50";

    @LocalServerPort
    int port;

    @Autowired
    LayerCache layerCache;

    @Autowired
    JsonMapper jsonMapper;

    @Autowired
    ActiveReleaseSource active;

    @Value("${spring.datasource.url}")
    String jdbcUrl;

    IngestCli.Result staged;
    IngestCli.Result noColumn;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        String url = EmbeddedPg.newDatabase();
        MultiSchemaFlywayRunner.migrateAll(EmbeddedPg.dataSource(url));
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("emberline.release.root", ROOT::toString);
        registry.add("emberline.layer-cache.url", REDIS::url);
    }

    @BeforeAll
    void stage() throws InterruptedException {
        noColumn = IngestCli.run(jdbcUrl, ROOT, "stage", "v12layers-nocol");
        staged = IngestCli.run(jdbcUrl, ROOT, "stage", "v12layers");
        assertThat(staged.exit()).as(staged.json().toString()).isZero();
        IngestCli.Result active = IngestCli.run(jdbcUrl, ROOT, "activate", "v12layers", "--expect-sha256",
                Fixtures.sha256(ROOT.resolve("v12layers/SHA256SUMS")));
        assertThat(active.exit()).as(active.json().toString()).isZero();
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline && !"v12layers".equals(json(get("/api/v1/version")).path("release").path("release_key").asString(""))) {
            Thread.sleep(200);
        }
    }

    @AfterAll
    void stopRedis() {
        REDIS.stop();
    }

    // ---- L2-1 ------------------------------------------------------------------------------------------------------

    /** The event → SGG index equals the MAPPING §8.4 rule recomputed here from fire_events and aoi_list. */
    @Test
    void p2_1_sggResolutionOnFixtureEvents() throws SQLException {
        Map<String, List<Sgg>> byName = new LinkedHashMap<>();
        for (Sgg s : FireInfoFixture.sggs()) {
            byName.computeIfAbsent(s.name(), k -> new ArrayList<>()).add(s);
        }
        Set<String> expected = new TreeSet<>();
        long dropped = 0;
        List<String[]> homonym = new ArrayList<>();
        List<String[]> border = new ArrayList<>();
        List<String[]> unresolved = new ArrayList<>();
        for (Ev e : FireInfoFixture.events()) {
            if (e.sggAll() == null) {
                continue;
            }
            Set<String> names = new LinkedHashSet<>();
            for (String part : e.sggAll().split(",")) {
                if (!part.strip().isEmpty()) {
                    names.add(part.strip());
                }
            }
            for (String name : names) {
                List<Sgg> candidates = byName.getOrDefault(name, List.of());
                if (candidates.size() == 1) {
                    expected.add(e.id() + "|" + candidates.get(0).id());
                    if (!Objects.equals(candidates.get(0).sidoName(), e.sido())) {
                        border.add(new String[] {e.id(), candidates.get(0).id()});
                    }
                    continue;
                }
                List<Sgg> inSido = candidates.stream().filter(s -> e.sido() != null && e.sido().equals(s.sidoName())).toList();
                if (inSido.size() == 1) {
                    expected.add(e.id() + "|" + inSido.get(0).id());
                    String other = candidates.stream().map(Sgg::id).filter(id -> !id.equals(inSido.get(0).id())).findFirst().orElseThrow();
                    homonym.add(new String[] {e.id(), inSido.get(0).id(), other});
                } else {
                    dropped++;
                    unresolved.add(new String[] {e.id(), name});
                }
            }
        }
        Set<String> actual = new TreeSet<>(strings("SELECT event_id || '|' || sgg_id FROM fireinfo.fire_event_sgg WHERE release_id = "
                + releaseId("v12layers")));
        assertThat(actual).isEqualTo(expected);
        assertThat(staged.json().get("rows").get("fireinfo.fire_event_sgg").asLong()).isEqualTo(expected.size());
        assertThat(staged.json().get("rows").get("fireinfo.fire_event_sgg_dropped").asLong()).isEqualTo(dropped);
        assertThat(homonym).as("fixture events naming a homonym SGG").isNotEmpty();
        assertThat(border).as("fixture events crossing a SIDO border").isNotEmpty();
        // the fixture holds every KR event and the whole aoi_list, so the contract's full-release measurement applies:
        // homonyms 강서구·고성군·남구·동구·북구·서구·중구, "이름 기준 소속 12,047건 중 못 푼 것 2건" (MAPPING §8.4)
        assertThat(byName.entrySet().stream().filter(x -> x.getValue().size() > 1).map(Map.Entry::getKey).collect(Collectors.toSet()))
                .containsExactlyInAnyOrder("강서구", "고성군", "남구", "동구", "북구", "서구", "중구");
        assertThat(expected.size() + dropped).as("names of sgg_all").isEqualTo(12_047);
        assertThat(dropped).as("names not resolved").isEqualTo(2);

        // homonym: the SGG under the event's SIDO lists the event, the namesake in the other SIDO does not
        String[] h = homonym.get(0);
        Ev he = event(h[0]);
        assertThat(sggList(h[1], he, expected)).contains(he.id());
        assertThat(ids(ok(eventsPath("aoi_id=" + h[2], he.start(), he.end()) + "&limit=1000"))).doesNotContain(he.id());
        // border crossing: the SGG in the other SIDO lists the event
        String[] b = border.get(0);
        Ev be = event(b[0]);
        assertThat(sggList(b[1], be, expected)).contains(be.id());
        // dropped names: none of the name's SGGs lists the event through it
        for (String[] u : unresolved) {
            Ev ue = event(u[0]);
            for (Sgg s : byName.getOrDefault(u[1], List.of())) {
                if (!expected.contains(ue.id() + "|" + s.id())) {
                    assertThat(ids(ok(eventsPath("aoi_id=" + s.id(), ue.start(), ue.end()) + "&limit=1000"))).doesNotContain(ue.id());
                }
            }
        }
    }

    /** /fire-events?aoi_id=SGG over the event's period equals the expected index members in default order. */
    List<String> sggList(String sggId, Ev e, Set<String> index) {
        List<String> got = ids(ok(eventsPath("aoi_id=" + sggId, e.start(), e.end()) + "&limit=1000"));
        List<String> want = FireInfoFixture.events().stream().filter(x -> index.contains(x.id() + "|" + sggId))
                .filter(x -> FireInfoFixture.overlaps(x, e.start(), e.end())).sorted(FireInfoFixture.order("sum_weight")).map(Ev::id).toList();
        assertThat(got).as(sggId).isEqualTo(want);
        return got;
    }

    // ---- L2-2 ------------------------------------------------------------------------------------------------------

    @Test
    void p2_2_krIsCountryKorAndPrkComesOnlyThroughBbox() {
        String start = "2025-01-01";
        String end = "2025-12-31";
        List<Ev> kor = FireInfoFixture.events().stream().filter(e -> "KOR".equals(e.country()))
                .filter(e -> FireInfoFixture.overlaps(e, start, end)).sorted(FireInfoFixture.order("sum_weight")).toList();
        JsonNode kr = ok(eventsPath("aoi_id=KR", start, end) + "&limit=1000");
        assertThat(kr.get("total").asLong()).isEqualTo(kor.size());
        assertThat(ids(kr)).isEqualTo(kor.stream().limit(1000).map(Ev::id).toList());
        kr.get("events").forEach(e -> assertThat(e.get("country").asString()).isEqualTo("KOR"));
        assertThat(kr.get("aoi_id").asString()).isEqualTo("KR");
        assertThat(kr.has("bbox")).isFalse();

        Ev prk = FireInfoFixture.events().stream().filter(e -> "PRK".equals(e.country())).findFirst().orElseThrow();
        assertThat(ids(ok(eventsPath("aoi_id=KR", prk.start(), prk.end()) + "&limit=1000"))).doesNotContain(prk.id());
        double w = prk.lon() - 0.05;
        double s = prk.lat() - 0.05;
        double ea = prk.lon() + 0.05;
        double n = prk.lat() + 0.05;
        JsonNode box = ok(eventsPath("bbox=" + w + "," + s + "," + ea + "," + n, prk.start(), prk.end()) + "&limit=1000");
        assertThat(ids(box)).contains(prk.id());
        assertThat(ids(box)).isEqualTo(FireInfoFixture.events().stream()
                .filter(e -> e.lon() >= w && e.lon() <= ea && e.lat() >= s && e.lat() <= n)
                .filter(e -> FireInfoFixture.overlaps(e, prk.start(), prk.end())).sorted(FireInfoFixture.order("sum_weight")).map(Ev::id).toList());
        assertThat(box.get("coverage").get("available").asBoolean()).isTrue();
        assertThat(box.has("aoi_id")).isFalse();

        // SIDO_* = sido_nm == aoi_list.name
        String sidoName = (String) Duck.query("SELECT name FROM read_parquet(?) WHERE aoi_id = 'SIDO_47'", FireInfoFixture.AOI_LIST).get(0).get(0);
        List<String> sido = FireInfoFixture.events().stream().filter(e -> sidoName.equals(e.sido()))
                .filter(e -> FireInfoFixture.overlaps(e, start, end)).sorted(FireInfoFixture.order("sum_weight")).map(Ev::id).toList();
        assertThat(sido).as("fixture events of SIDO_47 in " + start.substring(0, 4)).isNotEmpty();
        JsonNode sidoList = ok(eventsPath("aoi_id=SIDO_47", start, end) + "&limit=1000");
        assertThat(sidoList.get("total").asLong()).isEqualTo(sido.size());
        assertThat(ids(sidoList)).isEqualTo(sido);

        // ADM1_ARG-* = 'ADM1_' + adm1_code
        String adm1 = FireInfoFixture.events().stream().filter(e -> "ARG".equals(e.country()) && e.adm1() != null).findFirst().orElseThrow().adm1();
        List<String> arg = FireInfoFixture.events().stream().filter(e -> adm1.equals(e.adm1()))
                .filter(e -> FireInfoFixture.overlaps(e, "2025-01-01", "2025-01-31")).sorted(FireInfoFixture.order("sum_weight")).map(Ev::id).toList();
        JsonNode adm1List = ok(eventsPath("aoi_id=ADM1_" + adm1, "2025-01-01", "2025-01-31") + "&limit=1000");
        assertThat(adm1List.get("total").asLong()).isEqualTo(arg.size());
        assertThat(ids(adm1List)).isEqualTo(arg.stream().limit(1000).toList());
        assertThat(adm1List.get("coverage").get("available").asBoolean()).isTrue();
    }

    // ---- L2-3 ------------------------------------------------------------------------------------------------------

    @Test
    void p2_3_periodFilterIsOverlapAtBothEdges() {
        Ev e = FireInfoFixture.events().stream().filter(x -> "KOR".equals(x.country()) && x.start().compareTo(x.end()) < 0)
                .findFirst().orElseThrow();
        String box = "bbox=" + (e.lon() - 0.001) + "," + (e.lat() - 0.001) + "," + (e.lon() + 0.001) + "," + (e.lat() + 0.001);
        String before = LocalDate.parse(e.start()).minusDays(1).toString();
        String after = LocalDate.parse(e.end()).plusDays(1).toString();
        assertThat(ids(ok(eventsPath(box, e.start(), e.start())))).as("end = start_date").contains(e.id());
        assertThat(ids(ok(eventsPath(box, e.end(), e.end())))).as("start = end_date").contains(e.id());
        assertThat(ids(ok(eventsPath(box, before, e.start())))).as("end = start_date, start before").contains(e.id());
        assertThat(ids(ok(eventsPath(box, e.end(), after)))).as("start = end_date, end after").contains(e.id());
        assertThat(ids(ok(eventsPath(box, before, before)))).as("whole period before start_date").doesNotContain(e.id());
        assertThat(ids(ok(eventsPath(box, after, after)))).as("whole period after end_date").doesNotContain(e.id());
    }

    // ---- L2-4 ------------------------------------------------------------------------------------------------------

    @Test
    void p2_4_sortTiesAndCursorPaging() {
        String start = "2025-01-01";
        String end = "2025-01-31";
        List<Ev> arg = FireInfoFixture.events().stream().filter(e -> "ARG".equals(e.country()))
                .filter(e -> FireInfoFixture.overlaps(e, start, end)).toList();
        assertThat(arg.size()).as("ARG events of the fixture month").isGreaterThan(300);
        for (String sort : List.of("sum_weight", "start_date", "burned_area_km2")) {
            List<String> want = arg.stream().sorted(FireInfoFixture.order(sort)).map(Ev::id).toList();
            String base = eventsPath("aoi_id=ADM0_ARG", start, end) + "&sort=" + sort;
            JsonNode big = ok(base + "&limit=300");
            assertThat(big.get("total").asLong()).isEqualTo(arg.size());
            assertThat(big.get("truncated").asBoolean()).isTrue();
            assertThat(big.get("count").asInt()).isEqualTo(300);
            assertThat(ids(big)).as(sort).isEqualTo(want.subList(0, 300));
            assertThat(pages(base, 100, 300)).as(sort + " three pages of 100 = one page of 300").isEqualTo(ids(big));
        }
        // the whole month by sum_weight through the cursor, ties (equal sum_weight) included
        List<String> want = arg.stream().sorted(FireInfoFixture.order("sum_weight")).map(Ev::id).toList();
        List<Ev> sorted = arg.stream().sorted(FireInfoFixture.order("sum_weight")).toList();
        boolean ties = false;
        for (int i = 1; i < sorted.size(); i++) {
            ties |= sorted.get(i).sumWeight().equals(sorted.get(i - 1).sumWeight());
        }
        assertThat(ties).as("the fixture month has equal sum_weight").isTrue();
        assertThat(pages(eventsPath("aoi_id=ADM0_ARG", start, end), 1000, Integer.MAX_VALUE)).isEqualTo(want);
    }

    List<String> pages(String base, int limit, int max) {
        List<String> out = new ArrayList<>();
        String cursor = null;
        do {
            JsonNode page = ok(base + "&limit=" + limit + (cursor == null ? "" : "&cursor=" + cursor));
            out.addAll(ids(page));
            cursor = page.get("next_cursor").isNull() ? null : page.get("next_cursor").asString();
            assertThat(page.get("truncated").asBoolean()).isEqualTo(cursor != null);
        } while (cursor != null && out.size() < max);
        return out;
    }

    // ---- L2-5 ------------------------------------------------------------------------------------------------------

    @Test
    void p2_5_areaRulesCoverageAndLimits() {
        problem(eventsPath("aoi_id=KR&bbox=" + KR_BOX, "2025-03-01", "2025-03-31"), 400, 40003);
        problem("/api/v1/fire-events?start=2025-03-01&end=2025-03-31", 400, 10001);
        JsonNode out = ok(eventsPath("bbox=" + EUROPE_BOX, "2025-03-01", "2025-03-31"));
        assertOutOfCoverage(out, "kr_arg_events");
        assertThat(out.get("events").size()).isZero();
        assertThat(out.get("count").asInt()).isZero();
        for (String neighbour : List.of("ADM0_PRY", "ADM0_JPN")) {
            JsonNode n = ok(eventsPath("aoi_id=" + neighbour, "2025-01-01", "2025-01-31"));
            assertOutOfCoverage(n, "kr_arg_events");
            assertThat(n.get("events").size()).isZero();
        }
        problem(eventsPath("aoi_id=ADM0_ZZZ", "2025-01-01", "2025-01-31"), 404, 20401);
        // fire_events_days 3700: start..end inclusive
        LocalDate s = LocalDate.parse("2010-01-01");
        ok(eventsPath("aoi_id=KR", s.toString(), s.plusDays(3699).toString()));
        problem(eventsPath("aoi_id=KR", s.toString(), s.plusDays(3700).toString()), 400, 40106);
        // layer_bbox_area_deg2 400: 21 × 20 = 420 deg²
        problem(eventsPath("bbox=120,30,141,50", "2025-03-01", "2025-03-31"), 400, 40105);
        ok(eventsPath("bbox=120,30,140,50", "2025-03-01", "2025-03-31"));
        // fire_events_limit_max 1000
        ok(eventsPath("aoi_id=KR", "2025-03-01", "2025-03-31") + "&limit=1000");
        problem(eventsPath("aoi_id=KR", "2025-03-01", "2025-03-31") + "&limit=1001", 400, 10001);
        problem(eventsPath("aoi_id=KR", "2025-03-31", "2025-03-01"), 400, 10001);
        problem("/api/v1/fire-events?aoi_id=KR&start=2025-03-01", 400, 10001);
        // CSV: the contract columns
        HttpResponse<String> csv = get(eventsPath("aoi_id=KR", "2025-03-01", "2025-03-31") + "&format=csv");
        assertThat(csv.statusCode()).isEqualTo(200);
        assertThat(csv.headers().firstValue("Content-Type").orElse("")).startsWith("text/csv");
        assertThat(csv.body().replace("﻿", "").split("\n")[0]).isEqualTo(String.join(",", EVENT_COLUMNS));
    }

    // ---- L2-6 ------------------------------------------------------------------------------------------------------

    @Test
    void p2_6_eventDetail() {
        problem("/api/v1/fire-events/KR_1", 400, 40002);
        problem("/api/v1/fire-events/XX_20001102_0001", 400, 40002);
        problem("/api/v1/fire-events/KR_19990101_0001", 404, 40402);
        for (String id : List.of(FireInfoFixture.events().stream().filter(e -> "KR".equals(e.region())).findFirst().orElseThrow().id(),
                FireInfoFixture.events().stream().filter(e -> e.burned() != null && e.burned() > 0).findFirst().orElseThrow().id(),
                FireInfoFixture.events().stream().filter(e -> "ARG".equals(e.region())).findFirst().orElseThrow().id())) {
            JsonNode d = ok("/api/v1/fire-events/" + id);
            List<Object> row = Duck.query("SELECT event_id, region, CAST(start_date AS VARCHAR), CAST(end_date AS VARCHAR), "
                    + "CAST(duration_days AS BIGINT), CAST(n_det AS BIGINT), sum_weight, frp_sum, frp_max, centroid_lat, centroid_lon, "
                    + "lat_min, lat_max, lon_min, lon_max, burned_area_km2, burn_confirmed, instrument, CAST(n_viirs AS BIGINT), "
                    + "CAST(n_modis AS BIGINT), country, adm1_code, sido_nm, sgg_cd, sgg_nm, sgg_all FROM read_parquet(?) WHERE event_id = ?",
                    FireInfoFixture.EVENTS, id).get(0);
            JsonNode event = d.get("event");
            assertThat(keys(event)).containsExactlyElementsOf(EVENT_COLUMNS);
            for (int i = 0; i < EVENT_COLUMNS.size(); i++) {
                same(event.get(EVENT_COLUMNS.get(i)), row.get(i), id + " " + EVENT_COLUMNS.get(i));
            }
            assertThat(d.get("burned_area_label").asString()).isEqualTo("위성 확인 연소 면적(하한)");
            assertThat(d.get("data_release").asString()).isEqualTo("data-v12layers");
        }
    }

    // ---- L2-7 ------------------------------------------------------------------------------------------------------

    @Test
    void p2_7_fwiAlwaysCarriesAsOfDate() {
        String asOf = (String) Duck.query("SELECT CAST(max(date) AS VARCHAR) FROM read_parquet(?) WHERE aoi_id = 'KR'", FireInfoFixture.FWI).get(0).get(0);
        String fileAsOf = (String) Duck.query("SELECT CAST(max(date) AS VARCHAR) FROM read_parquet(?)", FireInfoFixture.FWI).get(0).get(0);
        String name = (String) Duck.query("SELECT min(name) FROM read_parquet(?) WHERE aoi_id = 'KR'", FireInfoFixture.FWI).get(0).get(0);

        JsonNode latest = ok("/api/v1/aois/KR/fwi");
        assertThat(latest.get("as_of_date").asString()).isEqualTo(asOf);
        assertThat(latest.get("date").asString()).isEqualTo(asOf);
        assertThat(latest.get("badge").asString()).isEqualTo("fwi_rel_class");
        assertThat(latest.get("name").asString()).isEqualTo(name);
        assertThat(latest.get("notice").asString()).isNotBlank();
        assertThat(latest.get("coverage").get("available").asBoolean()).isTrue();
        assertThat(latest.get("coverage").get("scope").asString()).isEqualTo("kr_arg_fwi");
        assertThat(latest.get("series").size()).isZero();
        sameFwiDay(latest.get("day"), fwiRows("KR", asOf, asOf).get(0));

        JsonNode day = ok("/api/v1/aois/KR/fwi?date=2025-03-20");
        assertThat(day.get("as_of_date").asString()).isEqualTo(asOf);
        assertThat(day.get("date").asString()).isEqualTo("2025-03-20");
        sameFwiDay(day.get("day"), fwiRows("KR", "2025-03-20", "2025-03-20").get(0));

        String next = LocalDate.parse(asOf).plusDays(1).toString();
        JsonNode future = ok("/api/v1/aois/KR/fwi?date=" + next);
        assertThat(future.get("as_of_date").asString()).isEqualTo(asOf);
        assertThat(future.get("date").asString()).isEqualTo(next);
        assertThat(future.get("day").isNull()).isTrue();
        assertThat(future.get("coverage").get("available").asBoolean()).isTrue();

        JsonNode series = ok("/api/v1/aois/KR/fwi?start=2025-03-01&end=2025-03-31");
        assertThat(series.get("as_of_date").asString()).isEqualTo(asOf);
        assertThat(series.get("day").isNull()).isTrue();
        List<List<Object>> rows = fwiRows("KR", "2025-03-01", "2025-03-31");
        assertThat(series.get("series").size()).isEqualTo(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            sameFwiDay(series.get("series").get(i), rows.get(i));
        }

        JsonNode out = ok("/api/v1/aois/ADM0_JPN/fwi");
        assertOutOfCoverage(out, "kr_arg_fwi");
        assertThat(out.get("as_of_date").asString()).as("as_of_date also out of range").isEqualTo(fileAsOf);
        assertThat(out.get("day").isNull()).isTrue();
        assertThat(out.get("series").size()).isZero();

        problem("/api/v1/aois/KR/fwi?date=2025-03-20&start=2025-03-01&end=2025-03-31", 400, 40004);
        problem("/api/v1/aois/KR/fwi?date=2025-03-20&start=2025-03-01", 400, 40004);
        problem("/api/v1/aois/KR/fwi?start=2025-03-01", 400, 10001);
        ok("/api/v1/aois/KR/fwi?start=2025-01-01&end=2026-01-01");
        problem("/api/v1/aois/KR/fwi?start=2025-01-01&end=2026-01-02", 400, 40107);
        problem("/api/v1/aois/ADM0_ZZZ/fwi", 404, 20401);

        HttpResponse<String> csv = get("/api/v1/aois/KR/fwi?start=2025-03-01&end=2025-03-31&format=csv");
        assertThat(csv.statusCode()).isEqualTo(200);
        String[] lines = csv.body().replace("﻿", "").split("\n");
        assertThat(lines[0]).isEqualTo("aoi_id,date,fwi_mean,fwi_max,fwi_class,fwi_pct,fwi_rel_class,isi_mean,bui_mean,ffmc_mean,dc_mean,n_cells,as_of_date");
        assertThat(lines).hasSize(rows.size() + 1);
        assertThat(lines[1]).startsWith("KR,2025-03-01,").endsWith("," + asOf);
    }

    List<List<Object>> fwiRows(String aoiId, String start, String end) {
        return Duck.query(FWI_DAY_SQL + "AND date BETWEEN CAST(? AS DATE) AND CAST(? AS DATE) ORDER BY date", FireInfoFixture.FWI, aoiId, start, end);
    }

    void sameFwiDay(JsonNode day, List<Object> row) {
        assertThat(keys(day)).containsExactlyElementsOf(FWI_DAY_COLUMNS);
        for (int i = 0; i < FWI_DAY_COLUMNS.size(); i++) {
            same(day.get(FWI_DAY_COLUMNS.get(i)), row.get(i), row.get(0) + " " + FWI_DAY_COLUMNS.get(i));
        }
    }

    // ---- L2-8 ------------------------------------------------------------------------------------------------------

    /** One fixture cell: centre as stored (FLOAT), p_spread, nation of the centre. */
    record Cell(float lat, float lon, float p, String candAdm0) {
        String key() {
            return lat + "," + lon;
        }

        double storedLat() {
            return Double.parseDouble(Float.toString(lat));
        }

        double storedLon() {
            return Double.parseDouble(Float.toString(lon));
        }

        /** low &lt; 0.05 ≤ moderate ≤ 0.20 &lt; high, on the value as stored. */
        String level() {
            double v = Double.parseDouble(Float.toString(p));
            return v > 0.20 ? "high" : v >= 0.05 ? "moderate" : "low";
        }
    }

    @Test
    void p2_8_spreadLevelsAreasAndCoverage() throws IOException {
        List<Cell> cells = new ArrayList<>();
        for (List<Object> r : Duck.query("SELECT cell_lat, cell_lon, p_spread, cand_adm0 FROM read_parquet(?)", FireInfoFixture.SPREAD)) {
            cells.add(new Cell((Float) r.get(0), (Float) r.get(1), (Float) r.get(2), (String) r.get(3)));
        }
        List<Object> head = Duck.query("SELECT DISTINCT CAST(as_of_date AS VARCHAR), CAST(target_date AS VARCHAR), model, source_satellite "
                + "FROM read_parquet(?)", FireInfoFixture.SPREAD).get(0);

        JsonNode box = ok("/api/v1/spread-nowcast?bbox=" + KR_BOX);
        assertThat(box.get("as_of_date").asString()).isEqualTo(head.get(0));
        assertThat(box.get("target_date").asString()).isEqualTo(head.get(1));
        assertThat(box.get("model").asString()).isEqualTo(head.get(2));
        assertThat(box.get("source_satellite").asString()).isEqualTo(head.get(3));
        assertThat(box.get("weather_available").asBoolean()).isFalse();
        assertThat(box.get("notice").asString()).isNotBlank();
        List<Cell> inBox = cells.stream().filter(c -> c.storedLon() >= 124.5 && c.storedLon() <= 132 && c.storedLat() >= 33
                && c.storedLat() <= 38.7).toList();
        assertThat(cellKeys(box)).isEqualTo(new TreeSet<>(inBox.stream().map(Cell::key).toList()));
        assertThat(box.get("count").asInt()).isEqualTo(inBox.size());
        assertThat(inBox).as("the KR box holds Japanese cells").anyMatch(c -> "JPN".equals(c.candAdm0()));
        Map<String, Cell> byKey = new LinkedHashMap<>();
        cells.forEach(c -> byKey.put(c.key(), c));
        for (JsonNode c : box.get("cells")) {
            Cell file = byKey.get(key(c));
            assertThat(c.get("level").asString()).as(key(c) + " p=" + file.p()).isEqualTo(file.level());
            assertThat((float) c.get("p_spread").doubleValue()).isEqualTo(file.p());
            assertThat(c.get("cand_adm0").isNull() ? null : c.get("cand_adm0").asString()).isEqualTo(file.candAdm0());
        }
        for (String min : List.of("moderate", "high")) {
            List<String> levels = min.equals("high") ? List.of("high") : List.of("moderate", "high");
            JsonNode filtered = ok("/api/v1/spread-nowcast?bbox=" + KR_BOX + "&min_level=" + min);
            assertThat(cellKeys(filtered)).as(min).isEqualTo(new TreeSet<>(inBox.stream().filter(c -> levels.contains(c.level()))
                    .map(Cell::key).toList()));
        }

        PreparedGeometry kr = boundary("KR");
        GeometryFactory gf = new GeometryFactory();
        Set<String> inKr = new TreeSet<>(cells.stream()
                .filter(c -> kr.contains(gf.createPoint(new Coordinate(c.storedLon(), c.storedLat())))).map(Cell::key).toList());
        JsonNode aoi = ok("/api/v1/spread-nowcast?aoi_id=KR");
        assertThat(cellKeys(aoi)).isEqualTo(inKr);
        aoi.get("cells").forEach(c -> assertThat(c.get("cand_adm0").isNull() ? "" : c.get("cand_adm0").asString()).isNotEqualTo("JPN"));
        assertThat(aoi.get("coverage").get("available").asBoolean()).isTrue();
        assertThat(aoi.get("aoi_id").asString()).isEqualTo("KR");

        JsonNode jpn = ok("/api/v1/spread-nowcast?aoi_id=ADM0_JPN");
        assertOutOfCoverage(jpn, "kr_box_arg_spread");
        assertThat(jpn.get("cells").size()).isZero();
        assertThat(jpn.get("notice").asString()).isNotBlank();
        assertOutOfCoverage(ok("/api/v1/spread-nowcast?bbox=" + EUROPE_BOX), "kr_box_arg_spread");

        String seoul = "126.9,37.5,127.0,37.6";
        assertThat(cells.stream().filter(c -> c.storedLon() >= 126.9 && c.storedLon() <= 127.0 && c.storedLat() >= 37.5
                && c.storedLat() <= 37.6)).isEmpty();
        JsonNode none = ok("/api/v1/spread-nowcast?bbox=" + seoul);
        assertThat(none.get("cells").size()).isZero();
        assertThat(none.get("coverage").get("available").asBoolean()).as("in range, no candidate cell").isTrue();
        assertThat(none.get("notice").asString()).isNotBlank();

        problem("/api/v1/spread-nowcast?aoi_id=KR&bbox=" + KR_BOX, 400, 40003);
        problem("/api/v1/spread-nowcast", 400, 10001);
        problem("/api/v1/spread-nowcast?bbox=" + KR_BOX + "&format=csv", 406, 10003);
    }

    static Set<String> cellKeys(JsonNode response) {
        Set<String> out = new TreeSet<>();
        response.get("cells").forEach(c -> out.add(key(c)));
        return out;
    }

    static String key(JsonNode cell) {
        return (float) cell.get("cell_lat").doubleValue() + "," + (float) cell.get("cell_lon").doubleValue();
    }

    /** The AOI's boundary from the fixture geojson (Polygon / MultiPolygon). */
    static PreparedGeometry boundary(String aoiId) throws IOException {
        JsonNode fc = JSON.readTree(Files.readString(FireInfoFixture.BOUNDARIES));
        GeometryFactory gf = new GeometryFactory();
        for (JsonNode f : fc.get("features")) {
            if (!aoiId.equals(f.get("properties").get("aoi_id").asString())) {
                continue;
            }
            JsonNode g = f.get("geometry");
            List<Polygon> parts = new ArrayList<>();
            if ("Polygon".equals(g.get("type").asString())) {
                parts.add(polygon(gf, g.get("coordinates")));
            } else {
                g.get("coordinates").forEach(p -> parts.add(polygon(gf, p)));
            }
            Geometry geometry = gf.createMultiPolygon(parts.toArray(new Polygon[0]));
            return PreparedGeometryFactory.prepare(geometry);
        }
        throw new IllegalStateException("no boundary " + aoiId);
    }

    static Polygon polygon(GeometryFactory gf, JsonNode rings) {
        List<LinearRing> all = new ArrayList<>();
        for (JsonNode ring : rings) {
            List<Coordinate> coords = new ArrayList<>();
            ring.forEach(xy -> coords.add(new Coordinate(xy.get(0).doubleValue(), xy.get(1).doubleValue())));
            all.add(gf.createLinearRing(coords.toArray(new Coordinate[0])));
        }
        return gf.createPolygon(all.get(0), all.subList(1, all.size()).toArray(new LinearRing[0]));
    }

    /**
     * Review LIMITS: the fixture holds the whole spread file (10,189 cells), so the contract's measured level
     * distribution low 9,708 · moderate 338 · high 143 (getSpreadNowcast) and the KR box counts KR 147 = cand_adm0
     * KOR 98 · JPN 47 · null 2, ARG 10,042 = ARG 9,941 · PRY 39 · BRA 27 · URY 21 · BOL 14 (MAPPING §8.4) are pinned on
     * the served levels: the KR box plus the ARG box in four bands (each under layer_bbox_area_deg2), cells de-duplicated.
     */
    @Test
    void p2_8b_spreadLevelDistributionAndRegionCountsAsServed() {
        List<List<Object>> rows = Duck.query("SELECT region, cand_adm0, count(*) FROM read_parquet(?) GROUP BY ALL ORDER BY ALL",
                FireInfoFixture.SPREAD);
        Map<String, Long> byRegion = new TreeMap<>();
        rows.forEach(r -> byRegion.put(r.get(0) + "/" + r.get(1), ((Number) r.get(2)).longValue()));
        assertThat(byRegion).containsExactlyInAnyOrderEntriesOf(Map.of("KR/KOR", 98L, "KR/JPN", 47L, "KR/null", 2L,
                "ARG/ARG", 9941L, "ARG/PRY", 39L, "ARG/BRA", 27L, "ARG/URY", 21L, "ARG/BOL", 14L));

        Map<String, String> served = new LinkedHashMap<>();
        JsonNode kr = ok("/api/v1/spread-nowcast?bbox=" + KR_BOX);
        assertThat(kr.get("count").asInt()).isEqualTo(147);
        Map<String, Long> krAdm0 = new TreeMap<>();
        for (JsonNode c : kr.get("cells")) {
            krAdm0.merge(c.get("cand_adm0").isNull() ? "null" : c.get("cand_adm0").asString(), 1L, Long::sum);
            served.put(key(c), c.get("level").asString());
        }
        assertThat(krAdm0).containsExactlyInAnyOrderEntriesOf(Map.of("KOR", 98L, "JPN", 47L, "null", 2L));
        double[] bands = {-55.2, -46.775, -38.35, -29.925, -21.5};
        for (int i = 0; i < 4; i++) {
            JsonNode band = ok("/api/v1/spread-nowcast?bbox=-73.5," + bands[i] + ",-53.4," + bands[i + 1]);
            band.get("cells").forEach(c -> served.put(key(c), c.get("level").asString()));
        }
        assertThat(served).hasSize(10_189);
        Map<String, Long> levels = new TreeMap<>();
        served.values().forEach(l -> levels.merge(l, 1L, Long::sum));
        assertThat(levels).containsExactlyInAnyOrderEntriesOf(Map.of("low", 9_708L, "moderate", 338L, "high", 143L));

        Map<String, Long> recomputed = new TreeMap<>();
        for (List<Object> r : Duck.query("SELECT cell_lat, cell_lon, p_spread, cand_adm0 FROM read_parquet(?)", FireInfoFixture.SPREAD)) {
            recomputed.merge(new Cell((Float) r.get(0), (Float) r.get(1), (Float) r.get(2), (String) r.get(3)).level(), 1L, Long::sum);
        }
        assertThat(recomputed).isEqualTo(levels);
    }

    // ---- L2-9 ------------------------------------------------------------------------------------------------------

    @Test
    void p2_9_fireRegimeRangeAndNoneWithTopEvent() {
        JsonNode r = ok("/api/v1/aois/ADM0_ARG/fire-regime?start=2000-11&end=2000-12");
        List<List<Object>> rows = Duck.query(REGIME_SQL + "AND CAST(year AS INTEGER) * 100 + month BETWEEN 200011 AND 200012 ORDER BY year, month",
                FireInfoFixture.REGIME, "ADM0_ARG");
        assertThat(rows).hasSize(2);
        assertThat(r.get("months").size()).isEqualTo(2);
        for (int i = 0; i < rows.size(); i++) {
            sameRegimeMonth(r.get("months").get(i), rows.get(i));
        }
        assertThat(r.get("name").asString()).isEqualTo(Duck.query("SELECT min(name) FROM read_parquet(?) WHERE aoi_id = 'ADM0_ARG'",
                FireInfoFixture.REGIME).get(0).get(0));
        assertThat(r.get("coverage").get("scope").asString()).isEqualTo("kr_arg_regime");
        assertThat(r.get("data_release").asString()).isEqualTo("data-v12layers");

        List<Object> none = Duck.query("SELECT aoi_id, CAST(year AS BIGINT), CAST(month AS BIGINT) FROM read_parquet(?) "
                + "WHERE fire_regime = 'none' AND top_event_id IS NOT NULL ORDER BY aoi_id, year, month LIMIT 1", FireInfoFixture.REGIME).get(0);
        String ym = String.format("%d-%02d", ((Number) none.get(1)).longValue(), ((Number) none.get(2)).longValue());
        JsonNode n = ok("/api/v1/aois/" + none.get(0) + "/fire-regime?start=" + ym + "&end=" + ym);
        assertThat(n.get("months").size()).isEqualTo(1);
        long key = ((Number) none.get(1)).longValue() * 100 + ((Number) none.get(2)).longValue();
        sameRegimeMonth(n.get("months").get(0), Duck.query(REGIME_SQL + "AND CAST(year AS INTEGER) * 100 + month = ?", FireInfoFixture.REGIME,
                none.get(0), key).get(0));
        assertThat(n.get("months").get(0).get("fire_regime").asString()).isEqualTo("none");
        assertThat(n.get("months").get(0).get("top_event_id").isNull()).isFalse();

        long all = ((Number) Duck.query("SELECT count(*) FROM read_parquet(?) WHERE aoi_id = 'KR'", FireInfoFixture.REGIME).get(0).get(0)).longValue();
        assertThat(ok("/api/v1/aois/KR/fire-regime").get("months").size()).isEqualTo((int) all);

        JsonNode out = ok("/api/v1/aois/ADM0_JPN/fire-regime");
        assertOutOfCoverage(out, "kr_arg_regime");
        assertThat(out.get("months").size()).isZero();
        problem("/api/v1/aois/KR/fire-regime?start=2025-03&end=2025-01", 400, 10001);
        problem("/api/v1/aois/KR/fire-regime?start=2025-13", 400, 10001);
    }

    void sameRegimeMonth(JsonNode month, List<Object> row) {
        assertThat(keys(month)).containsExactlyElementsOf(REGIME_COLUMNS);
        for (int i = 0; i < REGIME_COLUMNS.size(); i++) {
            same(month.get(REGIME_COLUMNS.get(i)), row.get(i), row.get(0) + "-" + row.get(1) + " " + REGIME_COLUMNS.get(i));
        }
    }

    // ---- L2-10 -----------------------------------------------------------------------------------------------------

    @Test
    void p2_10_layerHeaders304RedisKeyAndAliases() {
        String events = eventsPath("aoi_id=KR", "2025-03-01", "2025-03-31");
        for (String path : List.of(events, "/api/v1/fire-events/" + FireInfoFixture.events().get(0).id(), "/api/v1/aois/KR/fwi",
                "/api/v1/spread-nowcast?bbox=" + KR_BOX, "/api/v1/aois/KR/fire-regime?start=2025-01&end=2025-03")) {
            HttpResponse<String> r = get(path);
            assertThat(r.statusCode()).as(path).isEqualTo(200);
            assertThat(r.headers().firstValue("Cache-Control")).as(path).contains(LAYER);
            String etag = r.headers().firstValue("ETag").orElseThrow();
            assertThat(r.headers().firstValue("X-Data-Version")).as(path).isPresent();
            HttpResponse<String> again = get(path, "If-None-Match", etag);
            assertThat(again.statusCode()).as(path).isEqualTo(304);
            assertThat(again.body()).isEmpty();
        }
        HttpResponse<String> error = get("/api/v1/fire-events/KR_1");
        assertThat(error.headers().firstValue("Cache-Control")).contains("no-store");

        // redis-cache key = route + data_version + normalized query (the shared layer cache, emberline.layer-cache)
        HttpResponse<String> first = get(events);
        String dataVersion = first.headers().firstValue("X-Data-Version").orElseThrow();
        String cached = REDIS.get("em:test:c:layer:fire-events:s1:" + dataVersion
                + ":aoi_id=KR&bbox=&start=2025-03-01&end=2025-03-31&min_det=1&sort=sum_weight&limit=100&cursor=");
        assertThat(cached).as("layer response stored under data_version + normalized query").isNotNull();
        assertThat(REDIS.get("em:test:c:layer:fwi:s1:" + dataVersion + ":aoi_id=KR&date=&start=&end=")).isNotNull();

        // hand-off aliases: aoi or aoi_id, same body as the canonical route; both together → 40003
        assertThat(get("/api/events?aoi=KR&start=2025-03-01&end=2025-03-31").body()).isEqualTo(first.body());
        HttpResponse<String> alias = get("/api/events?aoi_id=KR&start=2025-03-01&end=2025-03-31");
        assertThat(alias.body()).isEqualTo(first.body());
        assertThat(alias.headers().firstValue("Link").orElse("")).contains("/api/v1/fire-events?").contains("rel=\"canonical\"");
        problem("/api/events?aoi=KR&aoi_id=KR&start=2025-03-01&end=2025-03-31", 400, 40003);

        String fwi = get("/api/v1/aois/KR/fwi?date=2025-03-20").body();
        assertThat(get("/api/fwi?aoi=KR&date=2025-03-20").body()).isEqualTo(fwi);
        assertThat(get("/api/fwi?aoi_id=KR&date=2025-03-20").body()).isEqualTo(fwi);
        problem("/api/fwi?aoi=KR&aoi_id=KR&date=2025-03-20", 400, 40003);

        String spread = get("/api/v1/spread-nowcast?aoi_id=KR").body();
        assertThat(get("/api/spread?aoi=KR").body()).isEqualTo(spread);
        assertThat(get("/api/spread?aoi_id=KR").body()).isEqualTo(spread);
        assertThat(get("/api/spread?bbox=" + KR_BOX).body()).isEqualTo(get("/api/v1/spread-nowcast?bbox=" + KR_BOX).body());
        problem("/api/spread?aoi=KR&aoi_id=KR", 400, 40003);

        String regime = get("/api/v1/aois/ADM0_ARG/fire-regime").body();
        assertThat(get("/api/regime?aoi=ADM0_ARG").body()).isEqualTo(regime);
        assertThat(get("/api/regime?aoi_id=ADM0_ARG").body()).isEqualTo(regime);
        problem("/api/regime?aoi=ADM0_ARG&aoi_id=ADM0_ARG", 400, 40003);
    }

    // ---- one layer cache, round trip -------------------------------------------------------------------------

    /**
     * Every fire-info answer is written to the shared layer cache and read back on a hit: after the request, the bean is
     * asked with a computation that fails, so the answer can only come from the stored entry; re-serialized with the
     * application's JsonMapper it is the HTTP body byte for byte, and a second request serves the same body.
     */
    @Test
    void p2_12_layerCacheRoundTripReadsTheStoredAnswerBack() {
        ReleaseRef release = active.current().orElseThrow();
        String eventId = FireInfoFixture.events().get(0).id();
        record Case(String path, LayerRoute route, String query, Class<?> type) {
        }
        List<Case> cases = List.of(
                new Case(eventsPath("aoi_id=KR", "2025-03-01", "2025-03-31"), LayerRoute.FIRE_EVENTS,
                        "aoi_id=KR&bbox=&start=2025-03-01&end=2025-03-31&min_det=1&sort=sum_weight&limit=100&cursor=", FireEventListResponse.class),
                new Case("/api/v1/fire-events/" + eventId, LayerRoute.FIRE_EVENT, "event_id=" + eventId, FireEventDetailResponse.class),
                new Case("/api/v1/aois/KR/fwi", LayerRoute.FWI, "aoi_id=KR&date=&start=&end=", FwiResponse.class),
                new Case("/api/v1/spread-nowcast?aoi_id=KR", LayerRoute.SPREAD_NOWCAST, "aoi_id=KR&bbox=&min_level=low", SpreadNowcastResponse.class),
                new Case("/api/v1/aois/ADM0_ARG/fire-regime?start=2025-01&end=2025-03", LayerRoute.FIRE_REGIME,
                        "aoi_id=ADM0_ARG&start=2025-01&end=2025-03", FireRegimeResponse.class));
        for (Case c : cases) {
            HttpResponse<String> r = get(c.path());
            assertThat(r.statusCode()).as(c.path()).isEqualTo(200);
            String key = layerCache.key(c.route(), release, c.query());
            assertThat(key).isEqualTo("em:test:c:layer:" + c.route().code() + ":s1:" + release.dataVersion() + ":" + c.query());
            assertThat(REDIS.get(key)).as(key).isNotNull();
            @SuppressWarnings("unchecked")
            Class<Object> type = (Class<Object>) c.type();
            Object hit = layerCache.get(c.route(), release, c.query(), type, () -> {
                throw new AssertionError("not read back from the layer cache: " + key);
            });
            assertThat(hit).isInstanceOf(c.type());
            assertThat(jsonMapper.writeValueAsString(hit)).as(c.path()).isEqualTo(r.body());
            assertThat(get(c.path()).body()).as(c.path()).isEqualTo(r.body());
        }
    }

    // ---- L2-11 -----------------------------------------------------------------------------------------------------

    @Test
    void p2_11_loaderRefusesAMissingColumnAndRecordsCounts() throws SQLException {
        assertThat(noColumn.exit()).as(noColumn.json().toString()).isEqualTo(3);
        assertThat(noColumn.state()).isEqualTo("REJECTED");
        assertThat(noColumn.reasons()).contains("COLUMN_MISSING:fire_events.parquet:sgg_all");

        JsonNode rows = staged.json().get("rows");
        assertThat(rows.get("fireinfo.fire_event").asLong()).isEqualTo(FireInfoFixture.count(FireInfoFixture.EVENTS));
        assertThat(rows.get("fireinfo.fwi_day").asLong()).isEqualTo(FireInfoFixture.count(FireInfoFixture.FWI));
        assertThat(rows.get("fireinfo.fwi_clim_month").asLong()).isEqualTo(FireInfoFixture.count(FireInfoFixture.CLIM));
        assertThat(rows.get("fireinfo.spread_cell").asLong()).isEqualTo(FireInfoFixture.count(FireInfoFixture.SPREAD));
        assertThat(rows.get("fireinfo.spread_head").asLong()).isEqualTo(1);
        assertThat(rows.get("fireinfo.fire_regime_month").asLong()).isEqualTo(FireInfoFixture.count(FireInfoFixture.REGIME));
        assertThat(rows.get("fireinfo.layer").asLong()).isEqualTo(5);
        assertThat(rows.has("fireinfo.fire_event_sgg")).isTrue();
        assertThat(rows.has("fireinfo.fire_event_sgg_dropped")).isTrue();
        assertThat(staged.reasons()).isEmpty();

        // MAPPING §8.2 "싣지 않는 열" are not stored
        List<String> columns = strings("SELECT table_name || '.' || column_name FROM information_schema.columns WHERE table_schema = 'fireinfo'");
        assertThat(columns).contains("fire_event.sgg_all", "spread_cell.cand_adm0", "fire_regime_month.W", "fwi_day.fwi_rel_class")
                .doesNotContain("fire_event.burned_area_km2_near", "fire_event.pixel_area_km2", "fire_event.pixel_area_nominal_share",
                        "spread_cell.near_active_km", "spread_cell.wind_align", "spread_cell.weather_date", "fwi_day.level",
                        "fire_regime_month.level");
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    static String eventsPath(String area, String start, String end) {
        return "/api/v1/fire-events?" + area + "&start=" + start + "&end=" + end;
    }

    static Ev event(String id) {
        return FireInfoFixture.events().stream().filter(e -> e.id().equals(id)).findFirst().orElseThrow();
    }

    static List<String> ids(JsonNode list) {
        List<String> out = new ArrayList<>();
        list.get("events").forEach(e -> out.add(e.get("event_id").asString()));
        return out;
    }

    static List<String> keys(JsonNode object) {
        List<String> out = new ArrayList<>();
        object.properties().forEach(e -> out.add(e.getKey()));
        return out;
    }

    static void assertOutOfCoverage(JsonNode response, String scope) {
        JsonNode c = response.get("coverage");
        assertThat(c.get("available").asBoolean()).isFalse();
        assertThat(c.get("scope").asString()).isEqualTo(scope);
        assertThat(c.get("reason").asString()).isEqualTo("out_of_coverage");
    }

    /** JSON value = the file value as stored (a FLOAT compared as float, a DOUBLE exactly, null as null). */
    static void same(JsonNode json, Object file, String what) {
        assertThat(json).as(what + " present").isNotNull();
        if (file == null) {
            assertThat(json.isNull()).as(what + " null").isTrue();
        } else if (file instanceof Float f) {
            assertThat((float) json.doubleValue()).as(what).isEqualTo(f);
        } else if (file instanceof Double d) {
            assertThat(json.doubleValue()).as(what).isEqualTo(d);
        } else if (file instanceof Number n) {
            assertThat(json.asLong()).as(what).isEqualTo(n.longValue());
        } else if (file instanceof Boolean b) {
            assertThat(json.asBoolean()).as(what).isEqualTo(b);
        } else {
            assertThat(json.asString()).as(what).isEqualTo(file.toString());
        }
    }

    JsonNode ok(String path) {
        HttpResponse<String> r = get(path);
        assertThat(r.statusCode()).as(path + " " + r.body()).isEqualTo(200);
        return json(r);
    }

    void problem(String path, int status, int errorNo) {
        HttpResponse<String> r = get(path);
        assertThat(r.statusCode()).as(path + " " + r.body()).isEqualTo(status);
        assertThat(json(r).get("error_no").asInt()).as(path).isEqualTo(errorNo);
        assertThat(r.headers().firstValue("Cache-Control")).as(path).contains("no-store");
    }

    HttpResponse<String> get(String path, String... headers) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().timeout(Duration.ofSeconds(60));
        for (int i = 0; i + 1 < headers.length; i += 2) {
            b.header(headers[i], headers[i + 1]);
        }
        try {
            return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    static JsonNode json(HttpResponse<String> r) {
        return JSON.readTree(r.body());
    }

    long releaseId(String key) throws SQLException {
        return Long.parseLong(strings("SELECT id FROM ingest.release WHERE release_key = '" + key + "'").get(0));
    }

    List<String> strings(String sql) throws SQLException {
        List<String> out = new ArrayList<>();
        try (Connection c = EmbeddedPg.dataSource(jdbcUrl).getConnection(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
        }
        return out;
    }
}

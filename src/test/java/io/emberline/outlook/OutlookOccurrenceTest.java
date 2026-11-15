package io.emberline.outlook;

import static org.assertj.core.api.Assertions.assertThat;

import io.emberline.platform.flyway.MultiSchemaFlywayRunner;
import io.emberline.shared.cache.LayerCache;
import io.emberline.shared.cache.LayerRoute;
import io.emberline.shared.release.ActiveReleaseSource;
import io.emberline.shared.release.ReleaseRef;
import io.emberline.support.Contract;
import io.emberline.support.EmbeddedPg;
import io.emberline.support.Fixtures;
import io.emberline.support.IngestCli;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import redis.embedded.RedisServer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * outlook layer over real HTTP. Release {@code v1.2-2026-11-15} = owner fixture core-v11
 * (aoi_month etc., so the release stages) with aoi_list, aoi_boundaries and the two occurrence files of the owner
 * fixture core-v12f15 (real v1.2 values). Expected numbers come from the fixture rows with plain Java (DuckDB only
 * reads the parquet; point-in-polygon is an even-odd test written here, not JTS), never from the code under test (A1).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"management.server.port=0", "emberline.ratelimit.enabled=false", "emberline.release.poll-interval=1s",
                "emberline.layer-cache.env=test", "emberline.layer-cache.retry-after=200ms"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OutlookOccurrenceTest {

    static final String KEY = "v1.2-2026-11-15";
    static final String GRID = "grid_occurrence_nextmonth_latest.parquet";
    static final String AOI = "aoi_occurrence_nextmonth_latest.parquet";
    static final Path NEW_LAYERS = Fixtures.releaseRoot().resolve("core-v12f15");
    static final Path CORE_V11 = Fixtures.releaseRoot().resolve("core-v11");
    static final Path ROOT = newRoot();
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    static final String LAYER_CACHE = "public, max-age=0, s-maxage=3600, must-revalidate";
    static final String EXAMPLE_BBOX = "-68.5,-55,-67.5,-54.5";
    static final int REDIS_PORT = freePort();
    static final RedisServer REDIS = startRedis();

    @Autowired
    LayerCache layerCache;

    @Autowired
    ActiveReleaseSource active;

    @LocalServerPort
    int port;

    @Value("${spring.datasource.url}")
    String jdbcUrl;

    IngestCli.Result staged;
    List<Cell> cells;
    List<Row> rows;
    Map<String, String[]> aoiList;
    JsonNode boundaries;

    /** A fixture grid row (float32 values widened exactly by DuckDB's CAST to DOUBLE). */
    record Cell(long id, double lat, double lon, double pAny, double baselineP, boolean persistent) {
    }

    /** A fixture municipality row. */
    record Row(String aoiId, double pAny, boolean suspected) {
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        String url = EmbeddedPg.newDatabase();
        MultiSchemaFlywayRunner.migrateAll(EmbeddedPg.dataSource(url));
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("emberline.release.root", ROOT::toString);
        registry.add("emberline.layer-cache.url", () -> "redis://127.0.0.1:" + REDIS_PORT);
    }

    static Path newRoot() {
        try {
            Path root = Files.createDirectory(Files.createTempDirectory("emberline-f15p1").resolve("releases"));
            release(root.resolve(KEY));
            Path badColumn = release(root.resolve("bad-column"));
            rewrite(badColumn, GRID, "SELECT * EXCLUDE (baseline_p) FROM src");
            resum(badColumn);
            Path badYears = release(root.resolve("bad-nyears"));
            rewrite(badYears, AOI, "SELECT * REPLACE (CASE WHEN aoi_id = 'SGG_31140' THEN n_years + 0.5 ELSE n_years END AS n_years) FROM src");
            resum(badYears);
            return root;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** core-v11 files, then the v1.2 aoi_list / aoi_boundaries and the two occurrence files of core-v12f15. */
    static Path release(Path dir) throws IOException {
        Files.createDirectories(dir);
        try (Stream<Path> files = Files.list(CORE_V11)) {
            for (Path f : files.filter(Files::isRegularFile).toList()) {
                Files.copy(f, dir.resolve(f.getFileName()), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        for (String f : List.of("aoi_list.parquet", "aoi_boundaries.geojson", GRID, AOI)) {
            Files.copy(NEW_LAYERS.resolve(f), dir.resolve(f), StandardCopyOption.REPLACE_EXISTING);
        }
        resum(dir);
        return dir;
    }

    static void rewrite(Path dir, String file, String selectOverSrc) throws IOException {
        Path tmp = dir.resolve(file + ".src");
        Files.move(dir.resolve(file), tmp);
        duck("CREATE TABLE src AS SELECT * FROM read_parquet('" + tmp + "')", "COPY (" + selectOverSrc + ") TO '" + dir.resolve(file) + "' (FORMAT parquet)");
        Files.delete(tmp);
    }

    static void duck(String... sql) {
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement s = c.createStatement()) {
            for (String q : sql) {
                s.execute(q);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** SHA256SUMS over every file of the folder (like the owner's release folder). */
    static void resum(Path dir) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : files.filter(Files::isRegularFile).filter(f -> !f.getFileName().toString().equals("SHA256SUMS")).sorted().toList()) {
                sb.append(Fixtures.sha256(f)).append("  ").append(f.getFileName()).append('\n');
            }
        }
        Files.writeString(dir.resolve("SHA256SUMS"), sb.toString());
    }

    static int freePort() {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static RedisServer startRedis() {
        try {
            RedisServer server = RedisServer.newRedisServer().port(REDIS_PORT).bind("127.0.0.1").setting("save \"\"")
                    .setting("appendonly no").build();
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @BeforeAll
    void stageAndActivate() throws Exception {
        staged = IngestCli.run(jdbcUrl, ROOT, "stage", KEY);
        assertThat(staged.exit()).as(staged.json().toString()).isZero();
        IngestCli.Result active = IngestCli.run(jdbcUrl, ROOT, "activate", KEY, "--expect-sha256", Fixtures.sha256(ROOT.resolve(KEY + "/SHA256SUMS")));
        assertThat(active.exit()).as(active.json().toString()).isZero();
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline && !KEY.equals(json(get("/api/v1/version")).path("release").path("release_key").asString(""))) {
            Thread.sleep(200);
        }
        // F5: before any outlook request, the pod serializes the whole grid (map, full) and the KR list on its own
        String dataVersion = json(get("/api/v1/version")).path("release").path("data_version").asString();
        RedisClient client = RedisClient.create("redis://127.0.0.1:" + REDIS_PORT);
        try (StatefulRedisConnection<String, String> c = client.connect()) {
            while (System.nanoTime() < deadline && warmed.size() < 3) {
                warmed.clear();
                for (String k : List.of("occurrence-grid:s1:" + dataVersion + ":bbox=&min_p_any=0.0&fields=map",
                        "occurrence-grid:s1:" + dataVersion + ":bbox=&min_p_any=0.0&fields=full",
                        "occurrence-aois:s1:" + dataVersion + ":parent_id=KR")) {
                    String v = c.sync().get("em:test:c:layer:" + k);
                    if (v != null) {
                        warmed.put(k.substring(0, k.indexOf(':')) + k.substring(k.lastIndexOf(':')), v);
                    }
                }
                Thread.sleep(100);
            }
        } finally {
            client.shutdown();
        }
        cells = readCells();
        rows = readRows();
        aoiList = readAoiList();
        boundaries = JSON.readTree(Files.readString(NEW_LAYERS.resolve("aoi_boundaries.geojson")));
    }

    /** Layer-cache entries found before the first outlook request: route + ":" + normalized query → body. */
    final Map<String, String> warmed = new LinkedHashMap<>();

    @AfterAll
    void stopRedis() throws IOException {
        REDIS.stop();
    }

    // ---- fixture rows (plain reads) ----

    static List<Cell> readCells() throws SQLException {
        List<Cell> out = new ArrayList<>();
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT cell_id, CAST(lat AS DOUBLE), CAST(lon AS DOUBLE), CAST(p_any AS DOUBLE), "
                     + "CAST(baseline_p AS DOUBLE), persistent_source FROM read_parquet('" + NEW_LAYERS.resolve(GRID) + "') ORDER BY cell_id")) {
            while (rs.next()) {
                out.add(new Cell(rs.getLong(1), rs.getDouble(2), rs.getDouble(3), rs.getDouble(4), rs.getDouble(5), rs.getBoolean(6)));
            }
        }
        return out;
    }

    static List<Row> readRows() throws SQLException {
        List<Row> out = new ArrayList<>();
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT aoi_id, CAST(p_any AS DOUBLE), suspected_industrial FROM read_parquet('"
                     + NEW_LAYERS.resolve(AOI) + "') ORDER BY aoi_id")) {
            while (rs.next()) {
                out.add(new Row(rs.getString(1), rs.getDouble(2), rs.getBoolean(3)));
            }
        }
        return out;
    }

    /** aoi_id → {name, parent_aoi}. */
    static Map<String, String[]> readAoiList() throws SQLException {
        Map<String, String[]> out = new HashMap<>();
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT aoi_id, name, parent_aoi FROM read_parquet('" + NEW_LAYERS.resolve("aoi_list.parquet") + "')")) {
            while (rs.next()) {
                out.put(rs.getString(1), new String[] {rs.getString(2), rs.getString(3)});
            }
        }
        return out;
    }

    // ---- HTTP ----

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

    JsonNode ok(String path, String... headers) {
        HttpResponse<String> r = get(path, headers);
        assertThat(r.statusCode()).as(path + " " + r.body().substring(0, Math.min(400, r.body().length()))).isEqualTo(200);
        return json(r);
    }

    static JsonNode json(HttpResponse<String> r) {
        return JSON.readTree(r.body());
    }

    @SuppressWarnings("unchecked")
    static JsonNode example(String path, String example) {
        Map<String, Object> op = (Map<String, Object>) Contract.at("paths", path, "get");
        Map<String, Object> ok = (Map<String, Object>) ((Map<String, Object>) op.get("responses")).get("200");
        Map<String, Object> media = (Map<String, Object>) ((Map<String, Object>) ok.get("content")).get("application/json");
        Map<String, Object> ex = (Map<String, Object>) ((Map<String, Object>) media.get("examples")).get(example);
        return JSON.valueToTree(ex.get("value"));
    }

    @SuppressWarnings("unchecked")
    static List<String> csvColumns(String path) {
        Map<String, Object> op = (Map<String, Object>) Contract.at("paths", path, "get");
        Map<String, Object> ok = (Map<String, Object>) ((Map<String, Object>) op.get("responses")).get("200");
        Map<String, Object> csv = (Map<String, Object>) ((Map<String, Object>) ok.get("content")).get("text/csv");
        return (List<String>) ((Map<String, Object>) csv.get("schema")).get("x-csv-columns");
    }

    static Set<String> names(JsonNode object) {
        Set<String> out = new HashSet<>();
        object.properties().forEach(e -> out.add(e.getKey()));
        return out;
    }

    static double round5(double v) {
        return new BigDecimal(v).setScale(5, RoundingMode.HALF_UP).doubleValue();
    }

    static String vsNormal(double pAny, double baselineP) {
        double d = pAny - baselineP;
        return d >= 0.10 ? "higher" : d <= -0.10 ? "lower" : "similar";
    }

    // ---- P1-1 … P1-4 grid ----

    @Test
    void p1_1_gridWithoutBboxServesEveryCellAndCountMatches() {
        JsonNode g = ok("/api/v1/occurrence/grid");
        assertThat(cells).hasSize(52855);
        assertThat(g.get("count").asInt()).isEqualTo(52855);
        assertThat(g.get("cells").size()).isEqualTo(g.get("count").asInt());
        assertThat(g.has("bbox")).isFalse();
        JsonNode example = example("/occurrence/grid", "arg_tip");
        for (String head : List.of("target_month", "as_of_date", "model", "cell_size_deg", "coverage", "notice", "data_release")) {
            assertThat(g.get(head)).as(head).isEqualTo(example.get(head));
        }
        Map<Long, Cell> byId = cells.stream().collect(Collectors.toMap(Cell::id, c -> c));
        Iterator<JsonNode> it = g.get("cells").iterator();
        int i = 0;
        while (it.hasNext()) {
            JsonNode c = it.next();
            Cell f = byId.get(c.get("cell_id").asLong());
            assertThat(f).as("cell %s is a file row", c.get("cell_id")).isNotNull();
            assertThat(c.get("vs_normal").asString()).as("vs_normal of %s", f.id()).isEqualTo(vsNormal(f.pAny(), f.baselineP()));
            assertThat(c.get("p_any").doubleValue()).isEqualTo(round5(f.pAny()));
            assertThat(c.get("persistent_source").booleanValue()).isEqualTo(f.persistent());
            i++;
        }
        assertThat(i).isEqualTo(52855);
        // the contract's cell_id formula holds for every fixture cell (MAPPING §8.4: 52,855/52,855)
        assertThat(cells).allMatch(c -> c.id() == (long) Math.floor((c.lat() + 90) / 0.5) * 720 + (long) Math.floor((c.lon() + 180) / 0.5));
        assertThat(cells.stream().filter(Cell::persistent).count()).isEqualTo(259);
    }

    @Test
    void p1_1b_vsNormalAndBandDistributionsMatchTheContractMeasurement() {
        Map<String, Long> vs = cells.stream().collect(Collectors.groupingBy(c -> vsNormal(c.pAny(), c.baselineP()), TreeMap::new, Collectors.counting()));
        assertThat(vs).as("contract getGridOccurrence: higher 4,941 / similar 42,772 / lower 5,142")
                .containsEntry("higher", 4941L).containsEntry("similar", 42772L).containsEntry("lower", 5142L);
        JsonNode g = ok("/api/v1/occurrence/grid?fields=map");
        Map<String, Long> served = new TreeMap<>();
        g.get("cells").forEach(c -> served.merge(c.get("vs_normal").asString(), 1L, Long::sum));
        assertThat(served).isEqualTo(vs);
        assertThat(cells.stream().filter(c -> c.pAny() < 0.1).count()).as("band < 10 %").isEqualTo(25844);
    }

    @Test
    void p1_2_gridWithTheExampleBboxIsTheContractExample() {
        JsonNode g = ok("/api/v1/occurrence/grid?bbox=" + EXAMPLE_BBOX);
        assertThat(g).isEqualTo(example("/occurrence/grid", "arg_tip"));
        assertThat(g.get("count").asInt()).isEqualTo(2);
    }

    @Test
    void p1_3_minPAnyKeepsTheContractMeasuredCells() {
        JsonNode g = ok("/api/v1/occurrence/grid?min_p_any=0.1&fields=map");
        long expected = cells.stream().filter(c -> c.pAny() >= 0.1).count();
        assertThat(expected).isEqualTo(27011);
        assertThat(g.get("count").asInt()).isEqualTo(27011);
        assertThat(g.get("cells").size()).isEqualTo(27011);
        g.get("cells").forEach(c -> assertThat(c.get("p_any").doubleValue()).isGreaterThanOrEqualTo(0.1));
    }

    @Test
    void p1_4_fieldsMapServesOnlyTheSevenFieldsRoundedToFourDecimals() {
        JsonNode g = ok("/api/v1/occurrence/grid?fields=map");
        Set<String> seven = Set.of("cell_id", "lat", "lon", "p_any", "baseline_p", "vs_normal", "persistent_source");
        assertThat(g.get("count").asInt()).isEqualTo(52855);
        g.get("cells").forEach(c -> {
            assertThat(names(c)).isEqualTo(seven);
            assertThat(BigDecimal.valueOf(c.get("p_any").doubleValue()).stripTrailingZeros().scale()).isLessThanOrEqualTo(4);
        });
        JsonNode full = ok("/api/v1/occurrence/grid?bbox=" + EXAMPLE_BBOX + "&fields=full");
        assertThat(names(full.get("cells").get(0))).hasSize(12);
    }

    @Test
    void gridBboxRulesAndCoverage() {
        // a box without any file cell (open Pacific): no_cells; cells present but all below min_p_any: available, empty
        JsonNode ocean = ok("/api/v1/occurrence/grid?bbox=-140,-40,-139,-39");
        assertThat(ocean.get("count").asInt()).isZero();
        assertThat(ocean.get("coverage").get("available").booleanValue()).isFalse();
        assertThat(ocean.get("coverage").get("reason").asString()).isEqualTo("no_cells");
        JsonNode none = ok("/api/v1/occurrence/grid?bbox=" + EXAMPLE_BBOX + "&min_p_any=0.9");
        assertThat(none.get("cells").size()).isZero();
        assertThat(none.get("coverage").get("available").booleanValue()).isTrue();
        // area limit: Version.limits layer_bbox_area_deg2 (400 deg²) → 400 range_too_large 40105
        HttpResponse<String> big = get("/api/v1/occurrence/grid?bbox=100,0,121,20");
        assertThat(big.statusCode()).isEqualTo(400);
        assertThat(json(big).get("error_no").asInt()).isEqualTo(40105);
        assertThat(json(big).get("code").asString()).isEqualTo("range_too_large");
        assertThat(json(big).get("param").asString()).isEqualTo("bbox");
        assertThat(get("/api/v1/occurrence/grid?bbox=100,0,120,20").statusCode()).isEqualTo(200);
        // antimeridian box (west > east) is accepted
        assertThat(get("/api/v1/occurrence/grid?bbox=170,-20,-170,0").statusCode()).isEqualTo(200);
        HttpResponse<String> bad = get("/api/v1/occurrence/grid?bbox=1,2,3");
        assertThat(bad.statusCode()).isEqualTo(400);
        assertThat(json(bad).get("error_no").asInt()).isEqualTo(10001);
        assertThat(get("/api/v1/occurrence/grid?min_p_any=1.5").statusCode()).isEqualTo(400);
        assertThat(get("/api/v1/occurrence/grid?fields=all").statusCode()).isEqualTo(400);
    }

    // ---- P1-5 … P1-7 municipalities ----

    @Test
    void p1_5_krListHas252RowsSuspectedUnrankedOthersRankedOneTo248() {
        JsonNode list = ok("/api/v1/occurrence/aois?parent_id=KR");
        assertThat(list.get("parent_id").asString()).isEqualTo("KR");
        assertThat(list.get("count").asInt()).isEqualTo(252);
        assertThat(list.get("items").size()).isEqualTo(252);
        assertThat(list.get("coverage").get("available").booleanValue()).isTrue();
        assertThat(list.get("coverage").get("scope").asString()).isEqualTo("kr_sgg");
        List<String> expectedOrder = rows.stream().filter(r -> !r.suspected())
                .sorted(Comparator.comparingDouble(Row::pAny).reversed().thenComparing(Row::aoiId)).map(Row::aoiId).toList();
        assertThat(expectedOrder).hasSize(248);
        Set<String> suspected = rows.stream().filter(Row::suspected).map(Row::aoiId).collect(Collectors.toSet());
        assertThat(suspected).hasSize(4);
        Map<String, JsonNode> byId = new HashMap<>();
        list.get("items").forEach(item -> byId.put(item.get("aoi_id").asString(), item));
        for (String id : suspected) {
            assertThat(byId.get(id).has("rank")).isTrue();
            assertThat(byId.get(id).get("rank").isNull()).as(id).isTrue();
            assertThat(byId.get(id).get("suspected_industrial").booleanValue()).isTrue();
        }
        List<Integer> ranks = new ArrayList<>();
        for (int i = 0; i < expectedOrder.size(); i++) {
            JsonNode item = byId.get(expectedOrder.get(i));
            assertThat(item.get("rank").asInt()).as(expectedOrder.get(i)).isEqualTo(i + 1);
            ranks.add(item.get("rank").asInt());
        }
        assertThat(ranks).isEqualTo(IntStream.rangeClosed(1, 248).boxed().toList());
        JsonNode first = list.get("items").get(0);
        assertThat(first.get("unit").asString()).isEqualTo("aoi");
        assertThat(first.get("name_ko").asString()).isEqualTo(aoiList.get(first.get("aoi_id").asString())[0]);
        assertThat(first.get("grid_summary").isNull()).isTrue();
    }

    @Test
    void p1_6_sidoListsItsOwnRowsUnknownIs404AndOtherAoisAreOutOfCoverage() {
        JsonNode sido = ok("/api/v1/occurrence/aois?parent_id=SIDO_47");
        Set<String> expected = rows.stream().map(Row::aoiId)
                .filter(id -> aoiList.containsKey(id) && "SIDO_47".equals(aoiList.get(id)[1])).collect(Collectors.toSet());
        assertThat(expected).isNotEmpty();
        Set<String> served = new HashSet<>();
        sido.get("items").forEach(i -> served.add(i.get("aoi_id").asString()));
        assertThat(served).isEqualTo(expected);
        assertThat(sido.get("count").asInt()).isEqualTo(expected.size());
        HttpResponse<String> unknown = get("/api/v1/occurrence/aois?parent_id=NOPE");
        assertThat(unknown.statusCode()).isEqualTo(404);
        assertThat(json(unknown).get("error_no").asInt()).isEqualTo(20401);
        assertThat(json(unknown).get("code").asString()).isEqualTo("unknown_aoi");
        for (String other : List.of("ADM0_ARG", "WORLD")) {
            JsonNode out = ok("/api/v1/occurrence/aois?parent_id=" + other);
            assertThat(out.get("items").isArray()).isTrue();
            assertThat(out.get("items").size()).isZero();
            assertThat(out.get("count").asInt()).isZero();
            assertThat(out.get("coverage").get("available").booleanValue()).isFalse();
            assertThat(out.get("coverage").get("reason").asString()).isEqualTo("out_of_coverage");
        }
        assertThat(get("/api/v1/occurrence/aois").statusCode()).isEqualTo(400);
    }

    @Test
    void p1_7_sgg31140IsTheContractExamplePlusItsName() {
        JsonNode one = ok("/api/v1/aois/SGG_31140/occurrence");
        JsonNode example = example("/aois/{aoiId}/occurrence", "sgg_industrial");
        example.properties().forEach(e -> assertThat(one.get(e.getKey())).as(e.getKey()).isEqualTo(e.getValue()));
        Set<String> extra = names(one);
        extra.removeAll(names(example));
        assertThat(extra).containsExactly("name_ko");
        assertThat(one.get("name_ko").asString()).isEqualTo(aoiList.get("SGG_31140")[0]);
        assertThat(one.has("rank")).isFalse();
    }

    // ---- P1-8 grid summary ----

    @Test
    void p1_8_nonSggAoisAnswerTheGridSummaryWithoutPersistentCells() {
        int persistentSeen = 0;
        for (String aoi : List.of("KR", "SIDO_47", "ADM0_ARG")) {
            JsonNode s = ok("/api/v1/aois/" + aoi + "/occurrence");
            assertThat(s.get("unit").asString()).isEqualTo("grid_summary");
            assertThat(s.get("p_any").isNull()).isTrue();
            assertThat(s.get("vs_normal").isNull()).isTrue();
            assertThat(s.get("coverage").get("available").booleanValue()).isTrue();
            assertThat(s.get("coverage").get("scope").asString()).isEqualTo("world_land_detected");
            List<Cell> inside = cells.stream().filter(c -> inside(aoi, c.lon(), c.lat())).toList();
            List<Double> kept = inside.stream().filter(c -> !c.persistent()).map(Cell::pAny).sorted().toList();
            int persistent = inside.size() - kept.size();
            persistentSeen += persistent;
            JsonNode g = s.get("grid_summary");
            assertThat(g.get("n_cells").asInt()).as(aoi).isEqualTo(kept.size());
            assertThat(g.get("n_persistent").asInt()).as(aoi).isEqualTo(persistent);
            assertThat(g.get("bands").get("lt10").asLong()).isEqualTo(kept.stream().filter(p -> p < 0.1).count());
            assertThat(g.get("bands").get("p10_40").asLong()).isEqualTo(kept.stream().filter(p -> p >= 0.1 && p < 0.4).count());
            assertThat(g.get("bands").get("p40_70").asLong()).isEqualTo(kept.stream().filter(p -> p >= 0.4 && p < 0.7).count());
            assertThat(g.get("bands").get("ge70").asLong()).isEqualTo(kept.stream().filter(p -> p >= 0.7).count());
            assertThat(g.get("p_any_max").doubleValue()).isEqualTo(round5(kept.get(kept.size() - 1)));
            assertThat(g.get("p_any_median").doubleValue()).as("lower middle").isEqualTo(round5(kept.get((kept.size() - 1) / 2)));
        }
        assertThat(persistentSeen).as("a persistent-source cell lies in KR, SIDO_47 or ADM0_ARG, so the exclusion is exercised").isPositive();
        // an AOI without boundary cells in this release (fixture boundaries: KR, SIDO_*, SGG_*, ADM0_ARG)
        JsonNode jpn = ok("/api/v1/aois/ADM0_JPN/occurrence");
        assertThat(jpn.get("unit").asString()).isEqualTo("grid_summary");
        assertThat(jpn.get("grid_summary").isNull()).isTrue();
        assertThat(jpn.get("coverage").get("available").booleanValue()).isFalse();
        assertThat(jpn.get("coverage").get("reason").asString()).isEqualTo("no_cells");
        HttpResponse<String> unknown = get("/api/v1/aois/NOPE/occurrence");
        assertThat(unknown.statusCode()).isEqualTo(404);
        assertThat(json(unknown).get("error_no").asInt()).isEqualTo(20401);
    }

    /** Even-odd point-in-polygon over every ring of the AOI's GeoJSON Polygon / MultiPolygon. */
    boolean inside(String aoiId, double x, double y) {
        for (JsonNode f : boundaries.get("features")) {
            if (!aoiId.equals(f.get("properties").get("aoi_id").asString())) {
                continue;
            }
            JsonNode g = f.get("geometry");
            List<JsonNode> polygons = new ArrayList<>();
            if ("Polygon".equals(g.get("type").asString())) {
                polygons.add(g.get("coordinates"));
            } else {
                g.get("coordinates").forEach(polygons::add);
            }
            boolean in = false;
            for (JsonNode polygon : polygons) {
                for (JsonNode ring : polygon) {
                    int n = ring.size();
                    for (int i = 0, j = n - 1; i < n; j = i++) {
                        double xi = ring.get(i).get(0).doubleValue();
                        double yi = ring.get(i).get(1).doubleValue();
                        double xj = ring.get(j).get(0).doubleValue();
                        double yj = ring.get(j).get(1).doubleValue();
                        if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) {
                            in = !in;
                        }
                    }
                }
            }
            return in;
        }
        return false;
    }

    // ---- P1-10 headers ----

    @Test
    void p1_10_layerCacheHeadersEtagAnd304ErrorsNoStore() {
        for (String path : List.of("/api/v1/occurrence/grid?bbox=" + EXAMPLE_BBOX, "/api/v1/occurrence/aois?parent_id=SIDO_47",
                "/api/v1/aois/SGG_31140/occurrence", "/api/v1/aois/KR/occurrence")) {
            HttpResponse<String> r = get(path);
            assertThat(r.statusCode()).as(path).isEqualTo(200);
            assertThat(r.headers().firstValue("Cache-Control")).as(path).hasValue(LAYER_CACHE);
            String etag = r.headers().firstValue("ETag").orElseThrow();
            assertThat(r.headers().firstValue("X-Data-Version")).isPresent();
            HttpResponse<String> again = get(path, "If-None-Match", etag);
            assertThat(again.statusCode()).as(path).isEqualTo(304);
            assertThat(again.body()).isEmpty();
        }
        for (String path : List.of("/api/v1/aois/NOPE/occurrence", "/api/v1/occurrence/grid?bbox=0,0,90,90")) {
            HttpResponse<String> err = get(path);
            assertThat(err.statusCode()).isBetween(400, 499);
            assertThat(err.headers().firstValue("Cache-Control")).as(path).hasValue("no-store");
        }
    }

    // ---- P1-11 aliases ----

    @Test
    void p1_11_aliasesEqualTheirCanonicalResponsesAndBothNamesAre40003() {
        String canonicalGrid = get("/api/v1/occurrence/grid?bbox=" + EXAMPLE_BBOX).body();
        HttpResponse<String> grid = get("/api/occurrence?unit=grid&bbox=" + EXAMPLE_BBOX);
        assertThat(grid.statusCode()).isEqualTo(200);
        assertThat(grid.body()).isEqualTo(canonicalGrid);
        assertThat(grid.headers().firstValue("Link").orElse("")).contains("/api/v1/occurrence/grid");
        String canonicalAoi = get("/api/v1/aois/SGG_31140/occurrence").body();
        for (String name : List.of("aoi", "aoi_id")) {
            HttpResponse<String> one = get("/api/occurrence?" + name + "=SGG_31140");
            assertThat(one.statusCode()).as(name).isEqualTo(200);
            assertThat(one.body()).as(name).isEqualTo(canonicalAoi);
        }
        assertThat(get("/api/occurrence?aoi=KR").body()).isEqualTo(get("/api/v1/aois/KR/occurrence").body());
        HttpResponse<String> both = get("/api/occurrence?aoi=SGG_31140&aoi_id=SGG_31140");
        assertThat(both.statusCode()).isEqualTo(400);
        assertThat(json(both).get("error_no").asInt()).isEqualTo(40003);
        assertThat(json(both).get("code").asString()).isEqualTo("invalid_param");
        assertThat(both.headers().firstValue("Cache-Control")).hasValue("no-store");
    }

    // ---- P1-12 loader ----

    @Test
    void p1_12_loaderRecordsRowCountsAndRefusesBadFiles() throws SQLException {
        assertThat(staged.json().get("rows").get("outlook.grid_occurrence").asLong()).isEqualTo(52855);
        assertThat(staged.json().get("rows").get("outlook.aoi_occurrence").asLong()).isEqualTo(252);
        List<String> warnings = new ArrayList<>();
        staged.json().get("warnings").forEach(w -> warnings.add(w.asString()));
        assertThat(warnings).noneMatch(w -> w.contains("occurrence"));
        try (Connection c = EmbeddedPg.dataSource(jdbcUrl).getConnection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT n_years FROM outlook.aoi_occurrence a JOIN ingest.release r ON r.id = a.release_id "
                     + "WHERE r.release_key = '" + KEY + "' AND a.aoi_id = 'SGG_31140'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).isEqualTo(14);
        }
        IngestCli.Result missing = IngestCli.run(jdbcUrl, ROOT, "stage", "bad-column");
        assertThat(missing.exit()).isNotZero();
        assertThat(missing.state()).isEqualTo("REJECTED");
        assertThat(missing.reasons()).contains("COLUMN_MISSING:" + GRID + ":baseline_p");
        IngestCli.Result years = IngestCli.run(jdbcUrl, ROOT, "stage", "bad-nyears");
        assertThat(years.exit()).isNotZero();
        assertThat(years.state()).isEqualTo("REJECTED");
        assertThat(years.reasons()).contains("NOT_INTEGRAL:" + AOI + ":n_years 1 rows");
    }

    // ---- P1-13 CSV ----

    @Test
    void p1_13_csvColumnsAreTheContractXCsvColumns() {
        HttpResponse<String> grid = get("/api/v1/occurrence/grid?bbox=" + EXAMPLE_BBOX + "&format=csv");
        assertThat(grid.statusCode()).isEqualTo(200);
        assertThat(grid.headers().firstValue("Content-Type").orElse("")).startsWith("text/csv");
        assertThat(grid.headers().firstValue("Cache-Control")).hasValue(LAYER_CACHE);
        List<String> lines = grid.body().replace("\uFEFF", "").lines().toList();
        assertThat(lines.get(0)).isEqualTo(String.join(",", csvColumns("/occurrence/grid")));
        assertThat(lines).hasSize(3);
        assertThat(lines.get(1)).startsWith("50623,-54.75,-68.25,2026-12,");
        HttpResponse<String> accept = get("/api/v1/occurrence/grid?bbox=" + EXAMPLE_BBOX, "Accept", "text/csv");
        assertThat(accept.body()).isEqualTo(grid.body());
        HttpResponse<String> aois = get("/api/v1/occurrence/aois?parent_id=KR&format=csv");
        assertThat(aois.statusCode()).isEqualTo(200);
        List<String> aoiLines = aois.body().replace("\uFEFF", "").lines().toList();
        assertThat(aoiLines.get(0)).isEqualTo(String.join(",", csvColumns("/occurrence/aois")));
        assertThat(aoiLines).hasSize(253);
        assertThat(get("/api/v1/aois/KR/occurrence?format=csv").statusCode()).isEqualTo(406);
    }

    // ---- Redis (acceptance 10) ----

    @Test
    void redisKeyIsDataVersionPlusTheNormalizedQueryWithoutTtlAndErrorsAreNotCached() {
        HttpResponse<String> r = get("/api/v1/occurrence/aois?parent_id=SIDO_47");
        String dataVersion = r.headers().firstValue("X-Data-Version").orElseThrow();
        get("/api/v1/aois/NOPE/occurrence");
        get("/api/v1/occurrence/grid?bbox=0,0,90,90");
        RedisClient client = RedisClient.create("redis://127.0.0.1:" + REDIS_PORT);
        try (StatefulRedisConnection<String, String> c = client.connect()) {
            String key = "em:test:c:layer:occurrence-aois:s1:" + dataVersion + ":parent_id=SIDO_47";
            assertThat(c.sync().get(key)).isEqualTo(r.body());
            assertThat(c.sync().ttl(key)).isEqualTo(-1L);
            List<String> keys = c.sync().keys("em:test:c:layer:*");
            assertThat(keys).isNotEmpty().allMatch(k -> k.matches("^em:test:c:layer:(occurrence-grid|occurrence-aois|aoi-occurrence):s1:"
                    + Pattern.quote(dataVersion) + ":.+$"));
            assertThat(keys).noneMatch(k -> k.contains("NOPE") || k.contains("90.0"));
        } finally {
            client.shutdown();
        }
    }

    // ---- F4: one layer cache, round trip; F5: serialized at activation ----

    /**
     * Outlook bodies go through the shared layer cache: after the request, the bean is asked with a serialization that
     * fails, so the text can only come from the stored entry — it is the HTTP body byte for byte.
     */
    @Test
    void outlookBodiesAreReadBackFromTheSharedLayerCache() {
        ReleaseRef release = active.current().orElseThrow();
        Map<String, String[]> cases = new LinkedHashMap<>();
        cases.put("/api/v1/occurrence/aois?parent_id=SIDO_47", new String[] {"OCCURRENCE_AOIS", "parent_id=SIDO_47"});
        cases.put("/api/v1/aois/SGG_31140/occurrence", new String[] {"AOI_OCCURRENCE", "aoi_id=SGG_31140"});
        cases.put("/api/v1/aois/ADM0_JPN/occurrence", new String[] {"AOI_OCCURRENCE", "aoi_id=ADM0_JPN"});
        cases.put("/api/v1/occurrence/grid?bbox=" + EXAMPLE_BBOX, new String[] {"OCCURRENCE_GRID",
                "bbox=" + String.join(",", Stream.of(EXAMPLE_BBOX.split(",")).map(v -> String.valueOf(Double.parseDouble(v))).toList())
                        + "&min_p_any=0.0&fields=full"});
        cases.forEach((path, c) -> {
            HttpResponse<String> r = get(path);
            assertThat(r.statusCode()).as(path).isEqualTo(200);
            LayerRoute route = LayerRoute.valueOf(c[0]);
            String body = layerCache.json(route, release, c[1], () -> {
                throw new AssertionError("not read back from the layer cache: " + path);
            });
            assertThat(body).as(path).isEqualTo(r.body());
            assertThat(get(path).body()).as(path).isEqualTo(r.body());
        });
    }

    @Test
    void wholeGridAndKrListAreSerializedWhenThePodGetsTheRelease() {
        assertThat(warmed.keySet()).containsExactly("occurrence-grid:bbox=&min_p_any=0.0&fields=map",
                "occurrence-grid:bbox=&min_p_any=0.0&fields=full", "occurrence-aois:parent_id=KR");
        assertThat(warmed.get("occurrence-grid:bbox=&min_p_any=0.0&fields=map")).isEqualTo(get("/api/v1/occurrence/grid?fields=map").body());
        assertThat(warmed.get("occurrence-grid:bbox=&min_p_any=0.0&fields=full")).isEqualTo(get("/api/v1/occurrence/grid").body());
        assertThat(warmed.get("occurrence-aois:parent_id=KR")).isEqualTo(get("/api/v1/occurrence/aois?parent_id=KR").body());
    }
}

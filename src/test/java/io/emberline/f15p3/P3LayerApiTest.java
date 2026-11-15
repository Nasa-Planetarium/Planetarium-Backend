package io.emberline.f15p3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import io.emberline.platform.flyway.MultiSchemaFlywayRunner;
import io.emberline.support.Duck;
import io.emberline.support.EmbeddedPg;
import io.emberline.support.Fixtures;
import io.emberline.support.IngestCli;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import redis.embedded.RedisServer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * over real HTTP on the owner fixture core-v12f15. Release {@code v12f15} = core-v11 (the
 * cell month / boundaries / meta the other loaders need) plus core-v12f15's harmonized_cell_day_frp.parquet (280 real
 * rows), satellite_day_status.parquet and satellite_month_status.parquet, unchanged; its release_manifest.csv carries
 * the fixture manifest's own lines for those three files and generated lines for the core-v11 files. Release
 * {@code v12bad} = the same with one wrong sha256. Expected values are read from the fixture files with DuckDB and
 * computed in plain Java — never from our code.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"management.server.port=0", "emberline.ratelimit.enabled=false", "emberline.release.poll-interval=1s",
                "emberline.layer-cache.env=p3"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class P3LayerApiTest {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    static final Path NEW_LAYERS = Fixtures.releaseRoot().resolve("core-v12f15");
    static final Path CORE_V11 = Fixtures.releaseRoot().resolve("core-v11");
    static final String FRP = "harmonized_cell_day_frp.parquet";
    static final String SDS = "satellite_day_status.parquet";
    static final String SMS = "satellite_month_status.parquet";
    static final List<String> FROM_F15 = List.of(FRP, SDS, SMS);
    static final String META = "m2v_meta.json";
    /** The KR box of the fixture builder (and of MAPPING §8.4): 7.5° × 5.7° = 42.75 deg². */
    static final String KR_BOX = "124.5,33,132,38.7";
    static final String LAYER = "public, max-age=0, s-maxage=3600, must-revalidate";
    static final Path ROOT = newRoot();
    static final int REDIS_PORT = freePort();
    static final RedisServer REDIS = startRedis();

    @LocalServerPort
    int port;

    @Value("${spring.datasource.url}")
    String jdbcUrl;

    IngestCli.Result staged;
    IngestCli.Result bad;
    IngestCli.Result badMeta;
    String dataVersion;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        String url = EmbeddedPg.newDatabase();
        MultiSchemaFlywayRunner.migrateAll(EmbeddedPg.dataSource(url));
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("emberline.release.root", ROOT::toString);
        registry.add("emberline.layer-cache.url", () -> "redis://127.0.0.1:" + REDIS_PORT);
    }

    // ---- release folders ----

    static Path newRoot() {
        try {
            Path root = Files.createDirectory(Files.createTempDirectory("emberline-p3").resolve("releases"));
            release(root.resolve("v12f15"), false, false);
            release(root.resolve("v12bad"), true, false);
            release(root.resolve("v12meta"), false, true);
            return root;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * A stage folder in the real v1.2 layout (deploy/release/assemble.sh): the manifest lists {@code models/m2v_meta.json}
     * only, the folder has it there and at the top (where the loader reads it). {@code wrongFrpHash}: the FRP line's
     * sha256 is wrong; {@code wrongTopLevelMeta}: the top-level copy differs from the listed file in one byte (same size).
     */
    static void release(Path dir, boolean wrongFrpHash, boolean wrongTopLevelMeta) throws IOException {
        Files.createDirectories(dir);
        try (Stream<Path> files = Files.list(CORE_V11)) {
            for (Path f : files.filter(Files::isRegularFile).filter(f -> !f.getFileName().toString().equals("SHA256SUMS")).toList()) {
                Files.copy(f, dir.resolve(f.getFileName()), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        for (String f : FROM_F15) {
            Files.copy(NEW_LAYERS.resolve(f), dir.resolve(f), StandardCopyOption.REPLACE_EXISTING);
        }
        Map<String, String> fixtureLines = new LinkedHashMap<>();
        for (String line : Files.readAllLines(NEW_LAYERS.resolve("release_manifest.csv"))) {
            fixtureLines.put(line.split(",", -1)[0], line);
        }
        List<String> manifest = new ArrayList<>(List.of("file,rows,bytes,sha256"));
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : files.filter(Files::isRegularFile).sorted().toList()) {
                String name = f.getFileName().toString();
                if (FROM_F15.contains(name)) {
                    String line = fixtureLines.get(name);
                    manifest.add(wrongFrpHash && name.equals(FRP) ? line.substring(0, line.lastIndexOf(',') + 1) + "0".repeat(64) : line);
                } else {
                    String rows = name.endsWith(".parquet") ? Duck.query("SELECT count(*) FROM read_parquet(?)", f).get(0).get(0).toString() : "";
                    manifest.add((name.equals(META) ? "models/" : "") + name + "," + rows + "," + Files.size(f) + "," + Fixtures.sha256(f));
                }
            }
        }
        Files.write(dir.resolve("release_manifest.csv"), manifest);
        Files.copy(dir.resolve(META), Files.createDirectory(dir.resolve("models")).resolve(META));
        if (wrongTopLevelMeta) {
            String meta = Files.readString(dir.resolve(META));
            Files.writeString(dir.resolve(META), meta.replaceFirst("\"seed\": 42", "\"seed\": 43"));
        }
        StringBuilder sums = new StringBuilder();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : files.filter(Files::isRegularFile).sorted().toList()) {
                sums.append(Fixtures.sha256(f)).append("  ").append(f.getFileName()).append('\n');
            }
        }
        Files.writeString(dir.resolve("SHA256SUMS"), sums.toString());
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
    void stageAndActivate() throws InterruptedException {
        staged = IngestCli.run(jdbcUrl, ROOT, "stage", "v12f15");
        assertThat(staged.exit()).as(staged.json().toString()).isZero();
        bad = IngestCli.run(jdbcUrl, ROOT, "stage", "v12bad");
        badMeta = IngestCli.run(jdbcUrl, ROOT, "stage", "v12meta");
        assertThat(IngestCli.run(jdbcUrl, ROOT, "activate", "v12f15", "--expect-sha256",
                Fixtures.sha256(ROOT.resolve("v12f15/SHA256SUMS"))).exit()).isZero();
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        JsonNode release = null;
        while (System.nanoTime() < deadline) {
            release = json(get("/api/v1/version")).get("release");
            if (release != null && !release.isNull()) {
                break;
            }
            Thread.sleep(200);
        }
        assertThat(release).isNotNull();
        dataVersion = release.get("data_version").asString();
    }

    @AfterAll
    void stopRedis() throws IOException {
        REDIS.stop();
    }

    HttpResponse<String> get(String path, String... headers) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().timeout(Duration.ofSeconds(30));
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

    String scalar(String sql) throws SQLException {
        try (Connection c = EmbeddedPg.dataSource(jdbcUrl).getConnection(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    long releaseId(String key) throws SQLException {
        return Long.parseLong(scalar("SELECT id FROM ingest.release WHERE release_key = '" + key + "'"));
    }

    // ---- expected values from the fixture file ----

    /** Fixture FRP rows of [start, end] by date_local, cell_id: cell_id, date, frp_sum, frp_snpp_equiv_sum, frp_snpp_equiv_max, n_points. */
    static List<List<Object>> fixtureFrp(String start, String end) {
        return Duck.query("SELECT CAST(cell_id AS BIGINT), CAST(CAST(date_local AS DATE) AS VARCHAR), CAST(frp_sum AS DOUBLE), "
                + "CAST(frp_snpp_equiv_sum AS DOUBLE), CAST(frp_snpp_equiv_max AS FLOAT), CAST(n_points AS BIGINT) FROM read_parquet(?) "
                + "WHERE CAST(date_local AS DATE) BETWEEN CAST(? AS DATE) AND CAST(? AS DATE) ORDER BY 2, 1", NEW_LAYERS.resolve(FRP), start, end);
    }

    /** Zero sign dropped (the API writes -0.0 as 0). */
    static double z(double v) {
        return v == 0.0 ? 0.0 : v;
    }

    static float z(float v) {
        return v == 0.0f ? 0.0f : v;
    }

    static String rowText(long cellId, String date, double sum, double equivSum, float equivMax, long nPoints) {
        return cellId + " " + date + " " + z(sum) + " " + z(equivSum) + " " + z(equivMax) + " " + nPoints;
    }

    static List<String> expectedRows(List<List<Object>> rows) {
        List<String> out = new ArrayList<>();
        for (List<Object> r : rows) {
            out.add(rowText((Long) r.get(0), (String) r.get(1), (Double) r.get(2), (Double) r.get(3), (Float) r.get(4), (Long) r.get(5)));
        }
        return out;
    }

    static List<String> actualRows(JsonNode body) {
        List<String> out = new ArrayList<>();
        body.get("cells").forEach(c -> out.add(rowText(c.get("cell_id").asLong(), c.get("date").asString(), c.get("frp_sum").doubleValue(),
                c.get("frp_snpp_equiv_sum").doubleValue(), c.get("frp_snpp_equiv_max").floatValue(), c.get("n_points").asLong())));
        return out;
    }

    /** CellId contract formula: corner = ((id / 3600 − 900) / 10, (id % 3600 − 1800) / 10), center = corner + 0.05. */
    static double[] center(long cellId) {
        return new double[] {(cellId / 3600 - 900) / 10.0 + 0.05, (cellId % 3600 - 1800) / 10.0 + 0.05};
    }

    // ---- load ----

    @Test
    void stageLoadsThe280FixtureRowsAsStoredAndReportsTimeAndSize() throws SQLException {
        assertThat(staged.reasons()).isEmpty();
        List<String> warnings = new ArrayList<>();
        staged.json().get("warnings").forEach(w -> warnings.add(w.asString()));
        assertThat(warnings).noneMatch(w -> w.startsWith("MANIFEST_"));
        JsonNode rows = staged.json().get("rows");
        assertThat(rows.get("observation.cell_day_frp").asLong()).isEqualTo(280);
        assertThat(rows.get("observation.cell_day_frp#load_ms").asLong()).isPositive();
        assertThat(rows.get("observation.cell_day_frp#bytes").asLong()).isPositive();
        assertThat(rows.get("observation.cell_day_frp#index_bytes").asLong()).isPositive();
        long id = releaseId("v12f15");
        assertThat(scalar("SELECT count(*) FROM pg_indexes WHERE schemaname = 'observation' AND indexname = 'cell_day_frp_r" + id
                + "_key' AND indexdef LIKE '%UNIQUE%(date_local, cell_id)%'")).isEqualTo("1");
        // values as stored: Σ over the table equals Σ over the file (no recomputation)
        List<List<Object>> file = Duck.query("SELECT sum(CAST(n_points AS BIGINT)), count(*) FILTER (WHERE frp_sum = 0) FROM read_parquet(?)", NEW_LAYERS.resolve(FRP));
        assertThat(scalar("SELECT sum(n_points) FROM observation.cell_day_frp WHERE release_id = " + id)).isEqualTo(file.get(0).get(0).toString());
        assertThat(scalar("SELECT count(*) FROM observation.cell_day_frp WHERE frp_sum = 0 AND release_id = " + id)).isEqualTo(file.get(0).get(1).toString());
    }

    // ---- P3-1 (end to end) ----

    @Test
    void p3_1_aWrongSha256InReleaseManifestRefusesTheStageNamingTheFile() {
        assertThat(bad.exit()).as(bad.json().toString()).isEqualTo(3);
        assertThat(bad.state()).isEqualTo("REJECTED");
        assertThat(bad.reasons()).contains("MANIFEST_SHA256_MISMATCH:" + FRP);
    }

    /**
     * F2: the stage folder lists m2v_meta.json under {@code models/} as the real manifest does; the top-level copy the
     * loader reads is checked against that entry by base name — a matching copy stages (v12f15 above), a copy that
     * differs in one byte is refused naming the top-level file.
     */
    @Test
    void p3_1b_aTopLevelCopyOfAListedSubdirectoryFileIsCheckedByBaseName() throws IOException {
        assertThat(Files.readString(ROOT.resolve("v12f15/release_manifest.csv"))).contains("\nmodels/m2v_meta.json,")
                .doesNotContain("\nm2v_meta.json,");
        assertThat(staged.state()).isEqualTo("LOADED");
        assertThat(staged.json().toString()).doesNotContain("MANIFEST_UNLISTED:m2v_meta.json");
        assertThat(Files.size(ROOT.resolve("v12meta/m2v_meta.json"))).isEqualTo(Files.size(ROOT.resolve("v12meta/models/m2v_meta.json")));
        assertThat(badMeta.exit()).as(badMeta.json().toString()).isEqualTo(3);
        assertThat(badMeta.state()).isEqualTo("REJECTED");
        assertThat(badMeta.reasons()).containsExactly("MANIFEST_SHA256_MISMATCH:m2v_meta.json");
    }

    // ---- P3-2 ----

    @Test
    void p3_2_frpDayOverTheKrBoxReturnsExactlyTheFixtureRowsWithCellIdCenters() {
        HttpResponse<String> r = get("/api/v1/cells/frp?bbox=" + KR_BOX + "&start=2025-03-20&end=2025-03-31");
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        JsonNode body = json(r);
        List<List<Object>> fixture = fixtureFrp("2025-03-20", "2025-03-31");
        assertThat(fixture).isNotEmpty();
        assertThat(actualRows(body)).containsExactlyElementsOf(expectedRows(fixture));
        assertThat(body.get("count").asInt()).isEqualTo(fixture.size());
        for (JsonNode c : body.get("cells")) {
            double[] center = center(c.get("cell_id").asLong());
            assertThat(c.get("cell_lat").doubleValue()).isCloseTo(center[0], within(1e-9));
            assertThat(c.get("cell_lon").doubleValue()).isCloseTo(center[1], within(1e-9));
        }
        assertThat(body.get("freq").asString()).isEqualTo("day");
        assertThat(body.get("start").asString()).isEqualTo("2025-03-20");
        assertThat(body.get("end").asString()).isEqualTo("2025-03-31");
        assertThat(body.get("bbox").toString()).isEqualTo("[124.5,33.0,132.0,38.7]");
        assertThat(body.has("aoi_id")).isFalse();
        assertThat(body.get("coverage").toString()).isEqualTo("{\"available\":true,\"scope\":\"world_frp\",\"reason\":null}");
        assertThat(body.get("data_release").asString()).isEqualTo("data-v12f15");
        assertThat(body.get("notice").asString()).startsWith("불 세기(FRP, S-NPP 기준)");
        assertThat(body.has("method_version")).isFalse();
        // CSV: the contract x-csv-columns, same rows
        HttpResponse<String> csv = get("/api/v1/cells/frp?bbox=" + KR_BOX + "&start=2025-03-20&end=2025-03-31&format=csv");
        assertThat(csv.statusCode()).isEqualTo(200);
        assertThat(csv.headers().firstValue("Content-Type").orElse("")).startsWith("text/csv");
        List<String> lines = csv.body().lines().toList();
        assertThat(lines.get(0)).isEqualTo("cell_id,cell_lat,cell_lon,date,frp_sum,frp_snpp_equiv_sum,frp_snpp_equiv_max,n_points");
        assertThat(lines).hasSize(fixture.size() + 1);
        assertThat(lines.get(1)).startsWith(fixture.get(0).get(0) + ",");
    }

    @Test
    void p3_2_frpByAoiUsesTheKrCellSet() {
        HttpResponse<String> r = get("/api/v1/cells/frp?aoi_id=KR&start=2025-03-20&end=2025-03-31");
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        JsonNode body = json(r);
        assertThat(body.get("aoi_id").asString()).isEqualTo("KR");
        assertThat(body.has("bbox")).isFalse();
        List<String> fixture = expectedRows(fixtureFrp("2025-03-20", "2025-03-31"));
        List<String> actual = actualRows(body);
        assertThat(fixture).containsAll(actual);
        // every fixture row of a cell /cells places in KR for 2025-03 (center inside the boundary) is returned
        Set<Long> krCells = new HashSet<>();
        json(get("/api/v1/cells?aoi_id=KR&month=2025-03")).get("features").forEach(f -> krCells.add(f.get("properties").get("cell_id").asLong()));
        assertThat(krCells).isNotEmpty();
        List<String> mustHave = fixture.stream().filter(row -> krCells.contains(Long.parseLong(row.split(" ")[0]))).toList();
        assertThat(actual).containsAll(mustHave);
        assertThat(actual).isNotEmpty();
        assertThat(get("/api/v1/cells/frp?aoi_id=NOPE&start=2025-03-20&end=2025-03-31").statusCode()).isEqualTo(404);
    }

    // ---- P3-3 ----

    @Test
    void p3_3_frpMonthIsSumMaxSumPerCellAndMonthComputedFromTheFixtureRows() {
        HttpResponse<String> r = get("/api/v1/cells/frp?bbox=" + KR_BOX + "&start=2025-03-01&end=2026-11-15&freq=month");
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        JsonNode body = json(r);
        // plain Java over the fixture rows (date order within each cell and month)
        record Group(long cell, YearMonth month, double[] sums, float[] max, long[] n) {
        }
        Map<String, Group> groups = new LinkedHashMap<>();
        for (List<Object> row : fixtureFrp("2025-03-01", "2026-11-15")) {
            long cell = (Long) row.get(0);
            YearMonth ym = YearMonth.from(LocalDate.parse((String) row.get(1)));
            Group g = groups.computeIfAbsent(ym + " " + cell, k -> new Group(cell, ym, new double[2], new float[] {Float.NEGATIVE_INFINITY}, new long[1]));
            g.sums()[0] += (Double) row.get(2);
            g.sums()[1] += (Double) row.get(3);
            g.max()[0] = Math.max(g.max()[0], (Float) row.get(4));
            g.n()[0] += (Long) row.get(5);
        }
        List<Group> expected = groups.values().stream()
                .sorted((a, b) -> a.month().equals(b.month()) ? Long.compare(a.cell(), b.cell()) : a.month().compareTo(b.month())).toList();
        assertThat(expected).isNotEmpty();
        assertThat(body.get("count").asInt()).isEqualTo(expected.size());
        assertThat(body.get("freq").asString()).isEqualTo("month");
        for (int i = 0; i < expected.size(); i++) {
            Group g = expected.get(i);
            JsonNode c = body.get("cells").get(i);
            assertThat(c.get("cell_id").asLong()).isEqualTo(g.cell());
            assertThat(c.get("date").asString()).isEqualTo(g.month().atDay(1).toString());
            assertThat(c.get("frp_sum").doubleValue()).isCloseTo(z(g.sums()[0]), within(1e-9));
            assertThat(c.get("frp_snpp_equiv_sum").doubleValue()).isCloseTo(z(g.sums()[1]), within(1e-9));
            assertThat(c.get("frp_snpp_equiv_max").floatValue()).isEqualTo(z(g.max()[0]));
            assertThat(c.get("n_points").asLong()).isEqualTo(g.n()[0]);
        }
        Set<String> months = new HashSet<>();
        body.get("cells").forEach(c -> months.add(c.get("date").asString().substring(0, 7)));
        assertThat(months).containsExactlyInAnyOrder("2025-03", "2026-11");
    }

    // ---- P3-4 ----

    void assertProblem(String path, int status, String code, int errorNo, String param) {
        HttpResponse<String> r = get(path);
        assertThat(r.statusCode()).as(path + " " + r.body()).isEqualTo(status);
        JsonNode p = json(r);
        assertThat(p.get("code").asString()).as(path).isEqualTo(code);
        assertThat(p.get("error_no").asInt()).as(path).isEqualTo(errorNo);
        if (param != null) {
            assertThat(p.get("param").asString()).as(path).isEqualTo(param);
        }
        assertThat(r.headers().firstValue("Cache-Control")).as(path).hasValue("no-store");
        assertThat(r.headers().firstValue("ETag")).as(path).isEmpty();
    }

    @Test
    void p3_4_limitsAre400WithTheirErrorNo() {
        // limits = emberline.limits.* = the contract values (31 days, 24 months, 100 deg², 50,000 rows)
        assertThat(get("/api/v1/cells/frp?bbox=" + KR_BOX + "&start=2025-03-01&end=2025-03-31").statusCode()).isEqualTo(200);
        assertProblem("/api/v1/cells/frp?bbox=" + KR_BOX + "&start=2025-03-01&end=2025-04-01", 400, "range_too_large", 40104, "end");
        assertThat(get("/api/v1/cells/frp?bbox=" + KR_BOX + "&start=2024-12-01&end=2026-11-15&freq=month").statusCode()).isEqualTo(200);
        assertProblem("/api/v1/cells/frp?bbox=" + KR_BOX + "&start=2024-01-01&end=2026-01-31&freq=month", 400, "range_too_large", 40104, "end");
        assertThat(get("/api/v1/cells/frp?bbox=120,30,130,40&start=2025-03-20&end=2025-03-21").statusCode()).isEqualTo(200);
        assertProblem("/api/v1/cells/frp?bbox=120,30,130.1,40&start=2025-03-20&end=2025-03-21", 400, "range_too_large", 40105, "bbox");
        assertProblem("/api/v1/cells/frp?bbox=" + KR_BOX + "&aoi_id=KR&start=2025-03-20&end=2025-03-21", 400, "invalid_param", 40003, null);
        assertProblem("/api/v1/cells/frp?start=2025-03-20&end=2025-03-21", 400, "invalid_param", 10001, "bbox");
        assertProblem("/api/v1/cells/frp?bbox=" + KR_BOX + "&start=2025-03-21&end=2025-03-20", 400, "invalid_param", 10001, "start");
        assertProblem("/api/v1/cells/frp?bbox=" + KR_BOX + "&start=2025-03-20", 400, "invalid_param", 10001, "end");
    }

    // ---- P3-5 ----

    @Test
    void p3_5_aStoredMinusZeroIsWrittenAsZeroAndStaysARow() throws SQLException {
        List<Object> row = fixtureFrp("2026-11-01", "2026-11-15").get(0);
        long cell = (Long) row.get(0);
        String date = (String) row.get(1);
        long id = releaseId("v12f15");
        String where = " WHERE release_id = " + id + " AND cell_id = " + cell + " AND date_local = DATE '" + date + "'";
        update("UPDATE observation.cell_day_frp SET frp_sum = '-0'::float8, frp_snpp_equiv_sum = '-0'::float8, "
                + "frp_snpp_equiv_max = '-0'::real" + where);
        try {
            minusZeroRow(id, cell, date, (Long) row.get(5));
        } finally {
            // the stored values again (other rows compare with the file); the answers asked above have their own cache keys
            update("UPDATE observation.cell_day_frp SET frp_sum = '" + row.get(2) + "'::float8, frp_snpp_equiv_sum = '" + row.get(3)
                    + "'::float8, frp_snpp_equiv_max = '" + row.get(4) + "'::real" + where);
        }
    }

    void update(String sql) throws SQLException {
        try (Connection c = EmbeddedPg.dataSource(jdbcUrl).getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate(sql);
        }
    }

    void minusZeroRow(long id, long cell, String date, long nPoints) throws SQLException {
        assertThat(scalar("SELECT frp_sum::text || ' ' || frp_snpp_equiv_max::text FROM observation.cell_day_frp WHERE release_id = " + id
                + " AND cell_id = " + cell + " AND date_local = DATE '" + date + "'")).isEqualTo("-0 -0");
        double[] corner = {(cell / 3600 - 900) / 10.0, (cell % 3600 - 1800) / 10.0};
        String box = (corner[1] + 0.01) + "," + (corner[0] + 0.01) + "," + (corner[1] + 0.09) + "," + (corner[0] + 0.09);
        HttpResponse<String> r = get("/api/v1/cells/frp?bbox=" + box + "&start=" + date + "&end=" + date);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        assertThat(r.body()).doesNotContain("-0.0").doesNotContain(":-0");
        JsonNode cells = json(r).get("cells");
        assertThat(cells).hasSize(1);
        JsonNode c = cells.get(0);
        assertThat(c.get("cell_id").asLong()).isEqualTo(cell);
        for (String f : List.of("frp_sum", "frp_snpp_equiv_sum", "frp_snpp_equiv_max")) {
            assertThat(Double.doubleToRawLongBits(c.get(f).doubleValue())).as(f).isZero();
        }
        assertThat(c.get("n_points").asLong()).isEqualTo(nPoints);
        HttpResponse<String> csv = get("/api/v1/cells/frp?bbox=" + box + "&start=" + date + "&end=" + date + "&format=csv");
        assertThat(csv.body().lines().toList().get(1)).doesNotContain(",-0").endsWith("," + date + ",0.0,0.0,0.0," + nPoints);
    }

    // ---- P3-6 ----

    static List<String> fixtureDays(String start, String end, Set<String> satellites) {
        List<String> out = new ArrayList<>();
        for (List<Object> r : Duck.query("SELECT satellite, CAST(CAST(date_utc AS DATE) AS VARCHAR), status, CAST(n_det_global AS BIGINT), "
                + "CAST(coverage AS VARCHAR), CAST(ratio_to_seasonal AS DOUBLE), CAST(note AS VARCHAR) FROM read_parquet(?) "
                + "WHERE CAST(date_utc AS DATE) BETWEEN CAST(? AS DATE) AND CAST(? AS DATE)", NEW_LAYERS.resolve(SDS), start, end)) {
            if (satellites.isEmpty() || satellites.contains((String) r.get(0))) {
                out.add(r.get(1) + " " + r.get(0) + " " + r.get(2) + " " + r.get(3) + " " + r.get(4) + " " + r.get(5) + " " + r.get(6));
            }
        }
        out.sort(String::compareTo);
        return out;
    }

    static List<String> days(JsonNode body) {
        List<String> out = new ArrayList<>();
        body.get("days").forEach(d -> out.add(d.get("date_utc").asString() + " " + d.get("satellite").asString() + " " + d.get("status").asString()
                + " " + (d.get("n_det_global").isNull() ? "null" : d.get("n_det_global").asLong()) + " " + text(d.get("coverage"))
                + " " + (d.get("ratio_to_seasonal").isNull() ? "null" : d.get("ratio_to_seasonal").doubleValue()) + " " + text(d.get("note"))));
        return out;
    }

    static String text(JsonNode n) {
        return n == null || n.isNull() ? "null" : n.asString();
    }

    @Test
    void p3_6_satelliteStatusDayRowsAreTheFileRowsByUtcDate() {
        String through = Duck.query("SELECT CAST(max(CAST(date_utc AS DATE)) AS VARCHAR) FROM read_parquet(?)", NEW_LAYERS.resolve(SDS)).get(0).get(0).toString();
        HttpResponse<String> r = get("/api/v1/satellite-status?start=2026-10-01&end=2026-11-14");
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        JsonNode body = json(r);
        assertThat(body.get("freq").asString()).isEqualTo("day");
        assertThat(body.get("start").asString()).isEqualTo("2026-10-01");
        assertThat(body.get("end").asString()).isEqualTo("2026-11-14");
        assertThat(body.get("through").asString()).isEqualTo(through);
        assertThat(body.get("months")).isEmpty();
        assertThat(body.get("data_release").asString()).isEqualTo("data-v12f15");
        List<String> expected = fixtureDays("2026-10-01", "2026-11-14", Set.of());
        assertThat(expected).isNotEmpty();
        assertThat(days(body)).containsExactlyElementsOf(expected);
        body.get("days").forEach(d -> assertThat(fieldNames(d)).containsExactly("satellite", "date_utc", "status", "n_det_global",
                "coverage", "ratio_to_seasonal", "note"));
        // defaults: the 30 UTC days up to the file's last date_utc
        JsonNode dflt = json(get("/api/v1/satellite-status"));
        assertThat(dflt.get("end").asString()).isEqualTo(through);
        assertThat(dflt.get("start").asString()).isEqualTo(LocalDate.parse(through).minusDays(29).toString());
        // 366 days is the limit, 367 → 400 range_too_large 40107
        assertThat(get("/api/v1/satellite-status?start=2025-01-01&end=2026-01-01").statusCode()).isEqualTo(200);
        assertProblem("/api/v1/satellite-status?start=2025-01-01&end=2026-01-02", 400, "range_too_large", 40107, "end");
    }

    @Test
    void p3_6_satelliteFilterKeepsTheListedSatellites() {
        JsonNode body = json(get("/api/v1/satellite-status?start=2026-10-01&end=2026-11-14&satellite=S-NPP,NOAA-20"));
        assertThat(days(body)).containsExactlyElementsOf(fixtureDays("2026-10-01", "2026-11-14", Set.of("S-NPP", "NOAA-20")));
        Set<String> sats = new HashSet<>();
        body.get("days").forEach(d -> sats.add(d.get("satellite").asString()));
        assertThat(sats).isNotEmpty().isSubsetOf("S-NPP", "NOAA-20");
        assertProblem("/api/v1/satellite-status?satellite=N", 400, "invalid_param", 10001, "satellite");
        assertProblem("/api/v1/satellite-status?freq=week", 400, "invalid_param", 10001, "freq");
    }

    @Test
    void p3_6_satelliteStatusMonthIsTheWholeMonthFile() {
        List<List<Object>> file = Duck.query("SELECT satellite, CAST(year AS INTEGER), CAST(month AS INTEGER), CAST(month_status AS VARCHAR), "
                + "CAST(obs_days AS INTEGER) FROM read_parquet(?) ORDER BY 2, 3, 1", NEW_LAYERS.resolve(SMS));
        assertThat(file).hasSize(934);
        JsonNode body = json(get("/api/v1/satellite-status?freq=month"));
        assertThat(body.get("freq").asString()).isEqualTo("month");
        assertThat(body.get("days")).isEmpty();
        assertThat(body.get("start").isNull()).isTrue();
        assertThat(body.get("end").isNull()).isTrue();
        List<String> actual = new ArrayList<>();
        body.get("months").forEach(m -> actual.add(m.get("satellite").asString() + " " + m.get("year").asInt() + " " + m.get("month").asInt()
                + " " + m.get("month_status").asString() + " " + (m.get("obs_days").isNull() ? "null" : m.get("obs_days").asInt())));
        List<String> expected = new ArrayList<>();
        file.forEach(f -> expected.add(f.get(0) + " " + f.get(1) + " " + f.get(2) + " " + f.get(3) + " " + f.get(4)));
        assertThat(actual).containsExactlyInAnyOrderElementsOf(expected);
        body.get("months").forEach(m -> assertThat(fieldNames(m)).containsExactly("satellite", "year", "month", "obs_days", "low_days",
                "missing_days", "days_in_month", "first_day", "last_day", "month_status"));
        // start/end narrow the months, satellite filters; no period limit
        long terra2025 = file.stream().filter(f -> f.get(0).equals("Terra") && ((Integer) f.get(1)) == 2025).count();
        assertThat(json(get("/api/v1/satellite-status?freq=month&satellite=Terra&start=2025-01-01&end=2025-12-31")).get("months")).hasSize((int) terra2025);
        assertThat(get("/api/v1/satellite-status?freq=month&start=2000-11-01&end=2026-11-14").statusCode()).isEqualTo(200);
    }

    @Test
    void p3_6_theStatusAliasEqualsTheCanonicalForm() {
        for (String q : List.of("?start=2026-11-01&end=2026-11-14", "?freq=month&satellite=Aqua", "")) {
            HttpResponse<String> alias = get("/api/status" + q);
            HttpResponse<String> canonical = get("/api/v1/satellite-status" + q);
            assertThat(alias.statusCode()).as(q).isEqualTo(200);
            assertThat(alias.body()).as(q).isEqualTo(canonical.body());
            assertThat(alias.headers().firstValue("Link")).as(q).hasValue("</api/v1/satellite-status" + q + ">; rel=\"canonical\"");
        }
    }

    static List<String> fieldNames(JsonNode node) {
        List<String> out = new ArrayList<>();
        node.properties().forEach(e -> out.add(e.getKey()));
        return out;
    }

    // ---- P3-7 ----

    @Test
    void p3_7_layerHeadersEtag304AndErrorsNoStore() {
        for (String p : List.of("/api/v1/cells/frp?bbox=" + KR_BOX + "&start=2025-03-25&end=2025-03-31",
                "/api/v1/cells/frp?bbox=" + KR_BOX + "&start=2025-03-25&end=2025-03-31&format=csv",
                "/api/v1/satellite-status?start=2026-11-01&end=2026-11-14", "/api/v1/satellite-status?freq=month")) {
            HttpResponse<String> first = get(p);
            assertThat(first.statusCode()).as(p).isEqualTo(200);
            assertThat(first.headers().firstValue("Cache-Control")).as(p).hasValue(LAYER);
            assertThat(first.headers().firstValue("X-Data-Version")).as(p).hasValue(dataVersion);
            assertThat(first.headers().firstValue("X-Method-Version")).as(p).isPresent();
            assertThat(first.headers().firstValue("Vary")).as(p).hasValue("Accept, Accept-Language");
            String etag = first.headers().firstValue("ETag").orElse("");
            assertThat(etag).as(p).matches("^\"[0-9a-f]{32}\"$");
            HttpResponse<String> again = get(p, "If-None-Match", etag);
            assertThat(again.statusCode()).as(p).isEqualTo(304);
            assertThat(again.body()).as(p).isEmpty();
            assertThat(again.headers().firstValue("Cache-Control")).as(p).hasValue(LAYER);
        }
        assertProblem("/api/v1/cells/frp?bbox=" + KR_BOX + "&start=2025-03-01&end=2025-04-01", 400, "range_too_large", 40104, "end");
        assertProblem("/api/v1/cells/frp?aoi_id=NOPE&start=2025-03-20&end=2025-03-21", 404, "unknown_aoi", 20401, "aoi_id");
        assertProblem("/api/v1/satellite-status?start=2025-01-01&end=2026-01-02", 400, "range_too_large", 40107, "end");
    }

    // ---- R5: Redis key = data_version + normalized query ----

    @Test
    void r5_layerAnswersAreCachedUnderDataVersionAndTheNormalizedQuery() {
        get("/api/v1/cells/frp?end=2025-03-29&start=2025-03-28&bbox=" + KR_BOX);
        get("/api/v1/satellite-status?satellite=NOAA-20,Aqua&end=2026-11-14&start=2026-11-10");
        get("/api/v1/cells/frp?aoi_id=NOPE&start=2025-03-28&end=2025-03-29");
        RedisClient client = RedisClient.create("redis://127.0.0.1:" + REDIS_PORT);
        try (StatefulRedisConnection<String, String> c = client.connect()) {
            List<String> keys = c.sync().keys("em:p3:c:layer:*");
            assertThat(keys).contains(
                    "em:p3:c:layer:frp:s1:" + dataVersion + ":bbox=124.5,33.0,132.0,38.7&end=2025-03-29&freq=day&start=2025-03-28",
                    "em:p3:c:layer:satellite-status:s1:" + dataVersion + ":end=2026-11-14&freq=day&satellite=Aqua,NOAA-20&start=2026-11-10");
            assertThat(keys).allMatch(k -> k.contains(":" + dataVersion + ":"));
            assertThat(keys).noneMatch(k -> k.contains("NOPE"));
        } finally {
            client.shutdown();
        }
    }
}

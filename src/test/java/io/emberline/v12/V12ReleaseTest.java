package io.emberline.v12;

import static org.assertj.core.api.Assertions.assertThat;

import io.emberline.platform.flyway.MultiSchemaFlywayRunner;
import io.emberline.support.Contract;
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
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * pl-v12 (data release v1.1 → v1.2): release {@code v12} = owner fixture core-v11 (real values) plus the v1.2 shapes the
 * owner measured on the real files — one cell-month value below 0, {@code static_removed*} / {@code static_n_points}
 * columns in harmonized_cell_month / harmonized_cell_day, satellite_day_status.parquet with the real column set
 * ({@code date_utc}, {@code note}, statuses ok/low/missing/partial, no data_source/updated_at) and
 * satellite_month_status.parquet. Release {@code v11} = core-v11 unchanged (rollback path).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"management.server.port=0", "emberline.ratelimit.enabled=false", "emberline.release.poll-interval=1s"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class V12ReleaseTest {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    static final Path CORE_V11 = Fixtures.releaseRoot().resolve("core-v11");
    static final Path ROOT = newRoot();
    static final long NEG_CELL = 4549888L;

    /** stage output of a plain core-v11 copy at 62779c6 (captured by running this staging at 62779c6). */
    static final List<String> V11_WARNINGS_AT_62779C6 = List.of(V11Baseline.WARNINGS);
    static final String V11_ROWS_AT_62779C6 = V11Baseline.ROWS;

    @LocalServerPort
    int port;

    @Value("${spring.datasource.url}")
    String jdbcUrl;

    IngestCli.Result v11;
    IngestCli.Result v12;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        String url = EmbeddedPg.newDatabase();
        MultiSchemaFlywayRunner.migrateAll(EmbeddedPg.dataSource(url));
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("emberline.release.root", ROOT::toString);
    }

    static Path newRoot() {
        try {
            Path root = Files.createDirectory(Files.createTempDirectory("emberline-v12").resolve("releases"));
            copy(root.resolve("v11"));
            Path v12 = copy(root.resolve("v12"));
            rewrite(v12, "harmonized_cell_month.parquet", "SELECT * REPLACE ("
                    + "CASE WHEN cell_id = " + NEG_CELL + " AND year = 2025 AND month = 3 THEN -0.25 ELSE value END AS value, "
                    + "CASE WHEN cell_id = " + NEG_CELL + " AND year = 2025 AND month = 3 THEN -0.25 ELSE value_lo END AS value_lo, "
                    + "CASE WHEN cell_id = " + NEG_CELL + " AND year = 2025 AND month = 3 THEN -0.25 ELSE value_hi END AS value_hi), "
                    + "0.0::DOUBLE AS static_removed, 0.0::DOUBLE AS static_removed_lo, 0.0::DOUBLE AS static_removed_hi FROM src");
            rewrite(v12, "harmonized_cell_day.parquet", "SELECT *, 0.0::DOUBLE AS static_removed, 0.0::DOUBLE AS static_removed_lo, "
                    + "0.0::DOUBLE AS static_removed_hi, 0::INTEGER AS static_n_points FROM src");
            write(v12.resolve("satellite_day_status.parquet"), "SELECT satellite, CAST(date_utc AS DATE) AS date_utc, n_det_ea::BIGINT AS n_det_ea, "
                    + "n_det_ea::BIGINT AS n_det_status, (n_det_ea * 40)::BIGINT AS n_det_global, 'full' AS coverage, 'ea' AS status_basis, "
                    + "900.0::DOUBLE AS baseline, (n_det_ea / 900.0)::DOUBLE AS ratio_to_baseline, status, note, 0::TINYINT AS n_sats_low_same_day, "
                    + "4::TINYINT AS n_other_sats, status AS status_raw, status AS status_r1, status AS status_r2, 880.0::DOUBLE AS seasonal_baseline, "
                    + "14::UINTEGER AS seasonal_years, (n_det_ea / 880.0)::DOUBLE AS ratio_to_seasonal FROM (VALUES "
                    + "('NOAA-20', '2026-11-12', 950, 'ok', CAST(NULL AS VARCHAR)), "
                    + "('NOAA-20', '2026-11-13', 300, 'low', 'low_multi_sat_reception_outage'), "
                    + "('S-NPP', '2026-11-13', 0, 'missing', 'earlier_nrt_gap_refilled'), "
                    + "('NOAA-21', '2026-11-14', 400, 'partial', 'partial_current_day')) t(satellite, date_utc, n_det_ea, status, note)");
            write(v12.resolve("satellite_month_status.parquet"), "SELECT satellite, year::SMALLINT AS year, month::TINYINT AS month, "
                    + "obs_days::SMALLINT AS obs_days, low_days::SMALLINT AS low_days, missing_days::SMALLINT AS missing_days, "
                    + "CAST(first_day AS DATE) AS first_day, CAST(last_day AS DATE) AS last_day, n_det_ea::BIGINT AS n_det_ea, "
                    + "days_in_month::SMALLINT AS days_in_month, month_status FROM (VALUES "
                    + "('NOAA-20', 2026, 10, 31, 0, 0, '2026-10-01', '2026-10-31', 31000, 31, 'complete'), "
                    + "('NOAA-20', 2026, 11, 14, 1, 0, '2026-11-01', '2026-11-14', 12000, 30, 'partial'), "
                    + "('S-NPP', 2026, 11, 13, 0, 1, '2026-11-01', '2026-11-14', 11000, 30, 'partial')) "
                    + "t(satellite, year, month, obs_days, low_days, missing_days, first_day, last_day, n_det_ea, days_in_month, month_status)");
            resum(v12);
            return root;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static Path copy(Path dir) throws IOException {
        Files.createDirectories(dir);
        try (Stream<Path> files = Files.list(CORE_V11)) {
            for (Path f : files.filter(Files::isRegularFile).toList()) {
                Files.copy(f, dir.resolve(f.getFileName()), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        return dir;
    }

    static void rewrite(Path dir, String file, String selectOverSrc) throws IOException {
        Path tmp = dir.resolve(file + ".src");
        Files.move(dir.resolve(file), tmp);
        duck("CREATE TABLE src AS SELECT * FROM read_parquet('" + tmp + "')", "COPY (" + selectOverSrc + ") TO '" + dir.resolve(file) + "' (FORMAT parquet)");
        Files.delete(tmp);
    }

    static void write(Path file, String select) {
        duck("COPY (" + select + ") TO '" + file + "' (FORMAT parquet)");
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

    @BeforeAll
    void stage() throws InterruptedException {
        v11 = IngestCli.run(jdbcUrl, ROOT, "stage", "v11");
        v12 = IngestCli.run(jdbcUrl, ROOT, "stage", "v12");
        assertThat(v12.exit()).as(v12.json().toString()).isZero();
        assertThat(IngestCli.run(jdbcUrl, ROOT, "activate", "v12", "--expect-sha256", Fixtures.sha256(ROOT.resolve("v12/SHA256SUMS"))).exit()).isZero();
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline && json(get("/api/v1/version")).path("release").isNull()) {
            Thread.sleep(200);
        }
    }

    HttpResponse<String> get(String path) {
        try {
            return HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().timeout(Duration.ofSeconds(30)).build(),
                    HttpResponse.BodyHandlers.ofString());
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

    List<String> warnings(IngestCli.Result r) {
        List<String> out = new ArrayList<>();
        r.json().get("warnings").forEach(n -> out.add(n.asString()));
        return out;
    }

    String scalar(String sql) throws SQLException {
        try (Connection c = EmbeddedPg.dataSource(jdbcUrl).getConnection(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    long releaseId(String key) throws SQLException {
        return Long.parseLong(scalar("SELECT id FROM ingest.release WHERE release_key = '" + key + "'"));
    }

    @Test
    void negativeCellValueStagesKeepsTheValueAndWarns() throws SQLException {
        assertThat(warnings(v12)).contains("NEGATIVE_VALUE:harmonized_cell_month.parquet:1 rows kept as stored; drawn as 0");
        assertThat(scalar("SELECT value FROM observation.cell_month WHERE release_id = " + releaseId("v12")
                + " AND cell_id = " + NEG_CELL + " AND year = 2025 AND month = 3")).isEqualTo("-0.25");
        // JSON keeps the stored value (no v1.2 contract line asks the API to clip; "draw as 0" is for drawing)
        JsonNode cells = json(get("/api/v1/cells?aoi_id=KR&month=2025-03&format=json"));
        boolean found = false;
        for (JsonNode f : cells.get("features")) {
            if (f.path("properties").path("cell_id").asLong() == NEG_CELL) {
                assertThat(f.get("properties").get("value").doubleValue()).isEqualTo(-0.25);
                found = true;
            }
        }
        assertThat(found).as(cells.toString().substring(0, Math.min(300, cells.toString().length()))).isTrue();
    }

    @Test
    void staticRemovedColumnsDoNotFailStage() {
        assertThat(v12.state()).isIn("LOADED", "ACTIVE");
        assertThat(v12.reasons()).isEmpty();
        assertThat(warnings(v12)).noneMatch(w -> w.contains("static_removed") || w.contains("static_n_points"));
    }

    @Test
    void realSatelliteDayStatusStagesAndStatusServesItsDaysAndNotes() throws SQLException {
        assertThat(v12.aliases()).contains("date_utc->date");
        assertThat(scalar("SELECT count(*) FROM provenance.satellite_day WHERE release_id = " + releaseId("v12"))).isEqualTo("4");
        // the contract SatelliteStatus shape (freq, days[] with date_utc, months[]); full rows are L3-6
        JsonNode s = json(get("/api/v1/satellite-status?start=2026-11-12&end=2026-11-14"));
        assertThat(s.get("freq").asString()).isEqualTo("day");
        assertThat(s.get("through").asString()).isEqualTo("2026-11-14");
        assertThat(s.get("months")).isEmpty();
        List<String> rows = new ArrayList<>();
        s.get("days").forEach(d -> rows.add(d.get("date_utc").asString() + " " + d.get("satellite").asString() + " " + d.get("status").asString()
                + " " + (d.get("note").isNull() ? "null" : d.get("note").asString())));
        assertThat(rows).containsExactly("2026-11-12 NOAA-20 ok null", "2026-11-13 NOAA-20 low low_multi_sat_reception_outage",
                "2026-11-13 S-NPP missing earlier_nrt_gap_refilled", "2026-11-14 NOAA-21 partial partial_current_day");
        assertThat(get("/api/status?start=2026-11-12&end=2026-11-14").body()).isEqualTo(get("/api/v1/satellite-status?start=2026-11-12&end=2026-11-14").body());
    }

    @Test
    void satelliteMonthStatusStagesIntoItsTable() throws SQLException {
        long id = releaseId("v12");
        assertThat(v12.json().get("rows").get("provenance.satellite_month").asLong()).isEqualTo(3);
        assertThat(scalar("SELECT string_agg(satellite || ' ' || year || '-' || month || ' ' || month_status || ' ' || obs_days || ' ' || last_day, ', ' "
                + "ORDER BY satellite, year, month) FROM provenance.satellite_month WHERE release_id = " + id))
                .isEqualTo("NOAA-20 2026-10 complete 31 2026-10-31, NOAA-20 2026-11 partial 14 2026-11-14, S-NPP 2026-11 partial 13 2026-11-14");
        assertThat(scalar("SELECT count(*) FROM pg_indexes WHERE schemaname = 'provenance' AND indexname = 'satellite_month_r" + id + "_key'"))
                .isEqualTo("1");
    }

    @Test
    void v11FolderStagesWithTheSameRowsAndWarningsAsAt62779c6() throws SQLException {
        assertThat(v11.exit()).as(v11.json().toString()).isZero();
        assertThat(warnings(v11)).isEqualTo(V11_WARNINGS_AT_62779C6);
        java.util.TreeMap<String, Long> rows = new java.util.TreeMap<>();
        v11.json().get("rows").properties().forEach(e -> rows.put(e.getKey(), e.getValue().asLong()));
        assertThat(rows.toString()).isEqualTo(V11_ROWS_AT_62779C6);
        assertThat(v11.aliases()).containsExactly(V11Baseline.ALIASES);
        assertThat(v11.state()).isEqualTo("LOADED");
        assertThat(v11.json().get("rows").has("provenance.satellite_month")).isFalse();
        assertThat(scalar("SELECT count(*) FROM provenance.satellite_month WHERE release_id = " + releaseId("v11"))).isEqualTo("0");
        assertThat(scalar("SELECT count(*) FROM pg_tables WHERE schemaname = 'provenance' AND tablename = 'satellite_month_r" + releaseId("v11") + "'"))
                .as("no satellite_month partition for a release without the file").isEqualTo("0");
    }

    @Test
    void methodTextShowsDisplayNamesAndCodesStayInternal() {
        JsonNode m = json(get("/api/v1/methods/current"));
        List<String> codes = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        m.get("value_sources").forEach(v -> {
            codes.add(v.get("value_source").asString());
            labels.add(v.get("label_ko").asString());
        });
        assertThat(codes).containsExactly("modis_m2v_terra", "modis_m2v", "snpp_observed", "n20_bridge", "n21_bridge",
                "modis_m2v_background", "modis_fallback_m2v_predicted");
        assertThat(labels.get(0)).contains("FireBridge-Terra");
        assertThat(labels.get(1)).contains("FireBridge");
        assertThat(labels.get(3)).contains("N20 Bridge");
        assertThat(m.get("rules").toString()).contains("FireBridge = m2v_xgb", "FireBridge-Terra = m2v_xgb_terra",
                "FireBridge Uncertainty Band = m2v_q10_q90", "N20 Bridge = n20_bridge", "총량 오차 1~2%, 월별 상관 0.97");
        assertThat(m.get("method_version").asString()).isEqualTo("v1-m2v-2026-11-14");
    }

    /**
     * / criterion 12: {@code Method.display_names} (internal id → screen name) = the contract's
     * {@code info.x-display-names} without its {@code description} / {@code performance_copy} keys, and
     * {@code Method.performance_copy} = that {@code performance_copy}; on /methods/current and /methods/{v}. The values
     * the method lists stay internal ids. The release carries neither field (DEVIATIONS).
     */
    @Test
    @SuppressWarnings("unchecked")
    void methodServesTheContractDisplayNamesAndPerformanceCopy() {
        Map<String, Object> names = new LinkedHashMap<>(Contract.at("info", "x-display-names"));
        Object copy = names.remove("performance_copy");
        names.remove("description");
        assertThat(names).containsOnlyKeys("m2v_xgb", "m2v_xgb_terra", "m2v_q10_q90", "n20_bridge");
        Map<String, Object> schema = (Map<String, Object>) Contract.at("components", "schemas", "Method", "properties");
        assertThat(schema).containsKeys("display_names", "performance_copy");
        JsonNode current = json(get("/api/v1/methods/current"));
        String version = current.get("method_version").asString();
        for (JsonNode m : List.of(current, json(get("/api/v1/methods/" + version)))) {
            assertThat(m.get("display_names").isObject()).isTrue();
            Map<String, Object> served = new LinkedHashMap<>();
            m.get("display_names").properties().forEach(e -> served.put(e.getKey(), e.getValue().asString()));
            assertThat(served).containsExactlyEntriesOf(names);
            assertThat(m.get("performance_copy").asString()).isEqualTo(copy).isEqualTo("총량 오차 1~2%, 월별 상관 0.97");
            List<String> fields = new ArrayList<>();
            m.properties().forEach(e -> fields.add(e.getKey()));
            assertThat(fields).as("only contract Method properties").isSubsetOf(schema.keySet());
            m.get("value_sources").forEach(v -> assertThat(names).doesNotContainValue(v.get("value_source").asString()));
        }
    }

    @Test
    void openapiDescriptionsShowDisplayNamesAndKeepTheEnum() throws IOException {
        String yaml = Files.readString(Path.of("contract/openapi.yaml"));
        // spec 272aedb: the display names moved from the info text into the info.x-display-names map (internal id → name)
        assertThat(yaml).contains("  x-display-names:", "    m2v_xgb: FireBridge (MODIS-to-VIIRS Harmonizer)",
                "    m2v_xgb_terra: FireBridge-Terra", "    m2v_q10_q90: FireBridge Uncertainty Band",
                "    n20_bridge: N20 Bridge", "    performance_copy: 총량 오차 1~2%, 월별 상관 0.97");
        assertThat(yaml).contains("enum: [modis_m2v_terra, modis_m2v, snpp_observed, n20_bridge, n21_bridge]");
    }
}

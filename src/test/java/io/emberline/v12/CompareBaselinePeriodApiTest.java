package io.emberline.v12;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import io.emberline.platform.flyway.MultiSchemaFlywayRunner;
import io.emberline.support.Contract;
import io.emberline.support.Duck;
import io.emberline.support.EmbeddedPg;
import io.emberline.support.Fixtures;
import io.emberline.support.IngestCli;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
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
 * aoi_month_compare replaced in data-v1.2-2026-11-15 stage r3: baseline_period, continuity_ratio and
 * continuity_source over real HTTP. Release {@code r3cmp} = owner fixture core-v11 with the v1.2 aoi_list / aoi_boundaries
 * of core-v12-layers and aoi_month / aoi_month_compare of owner fixture core-v12cmp (r3 rows of KR, WORLD, ADM0_ARG, SIDO_11,
 * SGG_31140, see build_fixture_v12cmp.sql); release {@code v11} = core-v11 unchanged (no such columns). Expected values
 * come from the fixture parquet (DuckDB) and the numbers of the brief, never from the code under test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"management.server.port=0", "emberline.ratelimit.enabled=false", "emberline.release.poll-interval=1s"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CompareBaselinePeriodApiTest {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    static final Path CORE_V11 = Fixtures.releaseRoot().resolve("core-v11");
    static final Path V12_LAYERS = Fixtures.releaseRoot().resolve("core-v12-layers");
    static final Path V12CMP = Fixtures.releaseRoot().resolve("core-v12cmp");
    static final Path COMPARE = V12CMP.resolve("aoi_month_compare.parquet");
    static final List<String> AOIS = List.of("KR", "WORLD", "ADM0_ARG", "SIDO_11", "SGG_31140");
    static final List<String> NEW_FIELDS = List.of("baseline_period", "continuity_ratio", "continuity_source");
    static final Path ROOT = newRoot();

    @LocalServerPort
    int port;

    @Value("${spring.datasource.url}")
    String jdbcUrl;

    IngestCli.Result staged;
    JsonNode v11Comparison;
    JsonNode v11Calendar;

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
            Path root = Files.createDirectory(Files.createTempDirectory("emberline-v12cmp").resolve("releases"));
            Path v11 = copyCore(root.resolve("v11"));
            Files.copy(CORE_V11.resolve("SHA256SUMS"), v11.resolve("SHA256SUMS"), StandardCopyOption.REPLACE_EXISTING);
            Path r3 = copyCore(root.resolve("r3cmp"));
            for (String f : List.of("aoi_list.parquet", "aoi_boundaries.geojson")) {
                Files.copy(V12_LAYERS.resolve(f), r3.resolve(f), StandardCopyOption.REPLACE_EXISTING);
            }
            for (String f : List.of("aoi_month.parquet", "aoi_month_compare.parquet")) {
                Files.copy(V12CMP.resolve(f), r3.resolve(f), StandardCopyOption.REPLACE_EXISTING);
            }
            resum(r3);
            return root;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path copyCore(Path dir) throws IOException {
        Files.createDirectories(dir);
        try (Stream<Path> files = Files.list(CORE_V11)) {
            for (Path f : files.filter(Files::isRegularFile).filter(f -> !f.getFileName().toString().equals("SHA256SUMS")).toList()) {
                Files.copy(f, dir.resolve(f.getFileName()), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        return dir;
    }

    /** SHA256SUMS over every regular file of the folder. */
    private static void resum(Path dir) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : files.filter(Files::isRegularFile).filter(f -> !f.getFileName().toString().equals("SHA256SUMS")).sorted().toList()) {
                sb.append(Fixtures.sha256(f)).append("  ").append(f.getFileName()).append('\n');
            }
        }
        Files.writeString(dir.resolve("SHA256SUMS"), sb.toString());
    }

    @BeforeAll
    void stageAndActivate() throws InterruptedException {
        IngestCli.Result v11 = IngestCli.run(jdbcUrl, ROOT, "stage", "v11");
        assertThat(v11.exit()).as(v11.json().toString()).isZero();
        staged = IngestCli.run(jdbcUrl, ROOT, "stage", "r3cmp");
        assertThat(staged.exit()).as(staged.json().toString()).isZero();
        activate("v11");
        v11Comparison = json(get("/api/v1/aois/KR/months/2024-08/comparison"));
        v11Calendar = json(get("/api/v1/aois/KR/calendar?start_year=2024&end_year=2024"));
        activate("r3cmp");
    }

    private void activate(String key) throws InterruptedException {
        IngestCli.Result active = IngestCli.run(jdbcUrl, ROOT, "activate", key, "--expect-sha256",
                Fixtures.sha256(ROOT.resolve(key + "/SHA256SUMS")));
        assertThat(active.exit()).as(active.json().toString()).isZero();
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline && !key.equals(json(get("/api/v1/version")).path("release").path("release_key").asString(""))) {
            Thread.sleep(200);
        }
        assertThat(json(get("/api/v1/version")).path("release").path("release_key").asString("")).isEqualTo(key);
    }

    // ---- smoke rows of the user's request (3) --------------------------------------------------------------------

    /** KR 2024-08 above / 2012-2025 / 0.6814 / own / 13 years; ADM0_ARG below / 2001-2025 / 1.0459; WORLD above / 2001-2025 / 1.0175. */
    @Test
    void smokeRowsKrArgWorld202408() {
        JsonNode kr = ok("/api/v1/aois/KR/months/2024-08/comparison");
        assertThat(kr.get("verdict").asString()).isEqualTo("above");
        assertThat(kr.get("baseline_period").asString()).isEqualTo("2012-2025");
        assertThat(kr.get("continuity_ratio").asDouble()).isCloseTo(0.6814, within(5e-5));
        assertThat(kr.get("continuity_source").asString()).isEqualTo("own");
        assertThat(kr.get("n_baseline_years").asInt()).isEqualTo(13);

        JsonNode arg = ok("/api/v1/aois/ADM0_ARG/months/2024-08/comparison");
        assertThat(arg.get("verdict").asString()).isEqualTo("below");
        assertThat(arg.get("baseline_period").asString()).isEqualTo("2001-2025");
        assertThat(arg.get("continuity_ratio").asDouble()).isCloseTo(1.0459, within(5e-5));
        assertThat(arg.get("continuity_source").asString()).isEqualTo("own");

        JsonNode world = ok("/api/v1/aois/WORLD/months/2024-08/comparison");
        assertThat(world.get("verdict").asString()).isEqualTo("above");
        assertThat(world.get("baseline_period").asString()).isEqualTo("2001-2025");
        assertThat(world.get("continuity_ratio").asDouble()).isCloseTo(1.0175, within(5e-5));
        assertThat(world.get("continuity_source").asString()).isEqualTo("own");

        for (String aoi : List.of("KR", "ADM0_ARG", "WORLD")) {
            assertComparisonIsTheFileRow(aoi, 2024, 8);
        }
    }

    /** KR 2010-08: percentile 92.86 (≥ 80) but the file says uncertain (2012-2025 region, month before 2012) — served as-is. */
    @Test
    void preBaselineMonthOfA2012RegionPassesUncertainThrough() {
        List<Object> file = fileRow("KR", 2010, 8);
        assertThat(file.get(0)).isEqualTo("uncertain");
        assertThat(((Number) file.get(4)).doubleValue()).isGreaterThanOrEqualTo(80.0);

        JsonNode c = ok("/api/v1/aois/KR/months/2010-08/comparison");
        assertThat(c.get("verdict").asString()).isEqualTo("uncertain");
        assertThat(c.get("percentile").asDouble()).isEqualTo(((Number) file.get(4)).doubleValue());
        assertThat(c.get("baseline_period").asString()).isEqualTo("2012-2025");
        assertComparisonIsTheFileRow("KR", 2010, 8);

        JsonNode aug = calendarMonth(ok("/api/v1/aois/KR/calendar?start_year=2010&end_year=2010"), 8);
        assertThat(aug.get("verdict").asString()).isEqualTo("uncertain");
        assertThat(aug.get("baseline_period").asString()).isEqualTo("2012-2025");

        // every month before 2012 of the 2012-2025 AOIs of the fixture is uncertain in the file and in the calendar
        List<List<Object>> before = Duck.query("SELECT aoi_id, year, month, verdict FROM read_parquet(?) "
                + "WHERE baseline_period = '2012-2025' AND year < 2012 ORDER BY 1, 2, 3", COMPARE);
        assertThat(before).isNotEmpty().allSatisfy(r -> assertThat(r.get(3)).isEqualTo("uncertain"));
        for (String aoi : List.of("KR", "SIDO_11", "SGG_31140")) {
            JsonNode cal = ok("/api/v1/aois/" + aoi + "/calendar?start_year=2000&end_year=2011");
            for (JsonNode m : cal.get("months")) {
                if (m.get("baseline_period").isNull()) {
                    continue; // 2000-01..2000-10: no row in the file
                }
                assertThat(m.get("verdict").asString()).as(aoi + " " + m.get("year") + "-" + m.get("month")).isEqualTo("uncertain");
            }
        }
    }

    /** continuity_source parent:<aoi_id> passes through (SIDO_11 inherits KR's ratio, SGG_31140 SIDO_31's). */
    @Test
    void parentSourceRowsPassThrough() {
        JsonNode sido = ok("/api/v1/aois/SIDO_11/months/2024-08/comparison");
        assertThat(sido.get("continuity_source").asString()).isEqualTo("parent:KR");
        assertThat(sido.get("continuity_ratio").asDouble()).isEqualTo(ok("/api/v1/aois/KR/months/2024-08/comparison")
                .get("continuity_ratio").asDouble());
        JsonNode sgg = ok("/api/v1/aois/SGG_31140/months/2024-08/comparison");
        assertThat(sgg.get("continuity_source").asString()).isEqualTo("parent:SIDO_31");
        assertThat(sgg.get("baseline_period").asString()).isEqualTo("2012-2025");
        assertComparisonIsTheFileRow("SIDO_11", 2024, 8);
        assertComparisonIsTheFileRow("SGG_31140", 2024, 8);
    }

    // ---- every verdict row ----------------------------------------------------------------------------------------

    /** Every calendar month of every fixture AOI (2000..2026) carries the file row's verdict and the three fields as stored. */
    @Test
    void everyCalendarRowIsTheFileRow() {
        Map<String, List<Object>> file = new HashMap<>();
        for (List<Object> r : Duck.query("SELECT aoi_id, CAST(year AS INTEGER), CAST(month AS INTEGER), verdict, baseline_period, "
                + "continuity_ratio, continuity_source FROM read_parquet(?)", COMPARE)) {
            file.put(r.get(0) + "|" + r.get(1) + "|" + r.get(2), r);
        }
        int matched = 0;
        Map<String, Long> byPeriod = new HashMap<>();
        for (String aoi : AOIS) {
            for (int[] range : new int[][] {{2000, 2013}, {2014, 2026}}) {
                JsonNode cal = ok("/api/v1/aois/" + aoi + "/calendar?start_year=" + range[0] + "&end_year=" + range[1]);
                for (JsonNode m : cal.get("months")) {
                    List<Object> row = file.get(aoi + "|" + m.get("year").asInt() + "|" + m.get("month").asInt());
                    String at = aoi + " " + m.get("year") + "-" + m.get("month");
                    if (row == null) {
                        for (String f : NEW_FIELDS) {
                            assertThat(m.get(f).isNull()).as(at + " " + f).isTrue();
                        }
                        continue;
                    }
                    matched++;
                    assertThat(m.get("verdict").asString()).as(at).isEqualTo(row.get(3));
                    assertThat(m.get("baseline_period").asString()).as(at).isEqualTo(row.get(4));
                    assertThat(m.get("continuity_ratio").asDouble()).as(at).isEqualTo(((Number) row.get(5)).doubleValue());
                    assertThat(m.get("continuity_source").asString()).as(at).isEqualTo(row.get(6));
                    byPeriod.merge(m.get("baseline_period").asString(), 1L, Long::sum);
                }
            }
        }
        assertThat(matched).isEqualTo(file.size()).isEqualTo(AOIS.size() * 313);
        Map<String, Long> fileByPeriod = new HashMap<>();
        for (List<Object> r : Duck.query("SELECT baseline_period, count(*) FROM read_parquet(?) GROUP BY 1", COMPARE)) {
            fileByPeriod.put((String) r.get(0), ((Number) r.get(1)).longValue());
        }
        assertThat(byPeriod).isEqualTo(fileByPeriod).containsEntry("2001-2025", 2L * 313).containsEntry("2012-2025", 3L * 313);
    }

    /** The loader stored the file rows unchanged: the partition equals the file on (key, verdict, three fields). */
    @Test
    void loadedPartitionEqualsTheFile() throws SQLException {
        assertThat(staged.json().get("rows").get("harmonized.aoi_month_compare").asLong()).isEqualTo(AOIS.size() * 313L);
        Set<String> expected = new TreeSet<>();
        for (List<Object> r : Duck.query("SELECT aoi_id, CAST(year AS INTEGER), CAST(month AS INTEGER), verdict, baseline_period, "
                + "continuity_ratio, continuity_source FROM read_parquet(?)", COMPARE)) {
            expected.add(r.get(0) + "|" + r.get(1) + "|" + r.get(2) + "|" + r.get(3) + "|" + r.get(4) + "|"
                    + ((Number) r.get(5)).doubleValue() + "|" + r.get(6));
        }
        Set<String> actual = new TreeSet<>();
        try (Connection c = DriverManager.getConnection(jdbcUrl, "postgres", null); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT aoi_id, year, month, verdict, baseline_period, continuity_ratio, continuity_source "
                     + "FROM harmonized.aoi_month_compare WHERE release_id = (SELECT id FROM ingest.release WHERE release_key = 'r3cmp')")) {
            while (rs.next()) {
                actual.add(rs.getString(1) + "|" + rs.getInt(2) + "|" + rs.getInt(3) + "|" + rs.getString(4) + "|" + rs.getString(5)
                        + "|" + rs.getDouble(6) + "|" + rs.getString(7));
            }
        }
        assertThat(actual).isEqualTo(expected);
        assertThat(staged.json().get("warnings").toString()).doesNotContain("COLUMN_ABSENT:aoi_month_compare.parquet");
    }

    // ---- baseline list (DEVIATIONS) -------------------------------------------------------------------

    @Test
    void baselineListFollowsTheRowsBaselinePeriod() {
        JsonNode kr = ok("/api/v1/aois/KR/months/2024-08/comparison");
        assertThat(years(kr)).containsExactlyElementsOf(range(2012, 2025, 2024));
        assertThat(usedCount(kr)).isEqualTo(kr.get("n_baseline_years").asInt()).isEqualTo(13);

        JsonNode kr2010 = ok("/api/v1/aois/KR/months/2010-08/comparison");
        assertThat(years(kr2010)).containsExactlyElementsOf(range(2012, 2025, 2010));
        assertThat(usedCount(kr2010)).isEqualTo(kr2010.get("n_baseline_years").asInt()).isEqualTo(14);

        JsonNode arg = ok("/api/v1/aois/ADM0_ARG/months/2024-08/comparison");
        assertThat(years(arg)).containsExactlyElementsOf(range(2001, 2025, 2024));
        assertThat(usedCount(arg)).isEqualTo(arg.get("n_baseline_years").asInt()).isEqualTo(24);
        assertThat(kr.get("rule").get("baseline_period").asString()).contains("2012-2025");
    }

    // ---- hand-off aliases and CSV ---------------------------------------------------------------------------------

    @Test
    void handoffAliasesServeTheSameFields() {
        JsonNode canonical = ok("/api/v1/aois/KR/months/2024-08/comparison");
        JsonNode alias = ok("/api/compare?aoi_id=KR&year=2024&month=8");
        for (String f : NEW_FIELDS) {
            assertThat(alias.get(f)).as(f).isEqualTo(canonical.get(f));
        }
        assertThat(alias).isEqualTo(canonical);

        JsonNode cal = ok("/api/v1/aois/SGG_31140/calendar?start_year=2024&end_year=2024");
        JsonNode calAlias = ok("/api/calendar?aoi_id=SGG_31140&start_year=2024&end_year=2024");
        assertThat(calAlias).isEqualTo(cal);
        assertThat(calendarMonth(calAlias, 8).get("continuity_source").asString()).isEqualTo("parent:SIDO_31");
    }

    @SuppressWarnings("unchecked")
    @Test
    void calendarCsvCarriesTheContractColumns() {
        Map<String, Object> csv = (Map<String, Object>) ((Map<String, Object>) Contract.at("paths", "/aois/{aoiId}/calendar", "get",
                "responses", "200", "content").get("text/csv")).get("schema");
        List<String> contract = (List<String>) csv.get("x-csv-columns");
        assertThat(contract.subList(contract.size() - 4, contract.size()))
                .containsExactly("method_version", "baseline_period", "continuity_ratio", "continuity_source");
        List<Object> file = fileRow("KR", 2024, 8);
        for (String path : List.of("/api/v1/aois/KR/calendar?start_year=2024&end_year=2024&format=csv",
                "/api/export.csv?type=calendar&aoi_id=KR&start_year=2024&end_year=2024")) {
            HttpResponse<String> r = get(path);
            assertThat(r.statusCode()).as(path).isEqualTo(200);
            String[] lines = r.body().replace("﻿", "").split("\n");
            List<String> header = List.of(lines[0].split(","));
            assertThat(header.subList(0, contract.size())).as(path).containsExactlyElementsOf(contract);
            String[] aug = lines[8].split(",", -1);
            assertThat(aug[1] + "-" + aug[2]).isEqualTo("2024-8");
            assertThat(aug[header.indexOf("verdict")]).isEqualTo(file.get(0));
            assertThat(aug[header.indexOf("baseline_period")]).isEqualTo(file.get(1));
            assertThat(aug[header.indexOf("continuity_ratio")]).isEqualTo(new BigDecimal(file.get(2).toString()).toPlainString());
            assertThat(aug[header.indexOf("continuity_source")]).isEqualTo(file.get(3));
        }
        assertThat(get("/api/v1/aois/KR/calendar?start_year=2024&end_year=2024&format=csv").body())
                .isEqualTo(get("/api/export.csv?type=calendar&aoi_id=KR&start_year=2024&end_year=2024").body());
    }

    // ---- contract -------------------------------------------------------------------------------------------------

    /** openapi declares the three fields on Comparison and CalendarMonth; the r3 example is the served KR 2024-08 row. */
    @SuppressWarnings("unchecked")
    @Test
    void contractDeclaresTheFieldsAndItsR3ExampleIsServed() {
        for (String schema : List.of("Comparison", "CalendarMonth")) {
            Map<String, Object> props = (Map<String, Object>) Contract.at("components", "schemas", schema).get("properties");
            assertThat(props).as(schema).containsKeys("baseline_period", "continuity_ratio", "continuity_source");
        }
        assertThat((List<String>) Contract.at("components", "schemas", "BaselinePeriod").get("enum"))
                .containsExactly("2001-2025", "2012-2025");
        String pattern = (String) Contract.at("components", "schemas", "ContinuitySource").get("pattern");
        for (List<Object> r : Duck.query("SELECT DISTINCT continuity_source FROM read_parquet(?)", COMPARE)) {
            assertThat((String) r.get(0)).matches(pattern);
        }
        assertThat("parent:ADM1_ATA+00?").matches(pattern);

        Map<String, Object> example = (Map<String, Object>) ((Map<String, Object>) ((Map<String, Object>) Contract.at("paths",
                "/aois/{aoiId}/months/{yearMonth}/comparison", "get", "responses", "200", "content", "application/json")
                .get("examples")).get("kr_2024_08_v12")).get("value");
        JsonNode served = ok("/api/v1/aois/KR/months/2024-08/comparison");
        for (Map.Entry<String, Object> e : example.entrySet()) {
            if (e.getKey().equals("baseline") || e.getKey().equals("rule")) {
                continue;
            }
            JsonNode v = served.get(e.getKey());
            assertThat(v).as(e.getKey()).isNotNull();
            Object want = e.getValue();
            if (want instanceof Number n) {
                assertThat(v.asDouble()).as(e.getKey()).isEqualTo(n.doubleValue());
            } else {
                assertThat(v.asString()).as(e.getKey()).isEqualTo(String.valueOf(want));
            }
        }
        List<Map<String, Object>> baseline = (List<Map<String, Object>>) example.get("baseline");
        for (int i = 0; i < baseline.size(); i++) {
            assertThat(served.get("baseline").get(i).get("year").asInt()).isEqualTo(baseline.get(i).get("year"));
            assertThat(served.get("baseline").get(i).get("value").asDouble()).isEqualTo(((Number) baseline.get(i).get("value")).doubleValue());
        }
    }

    // ---- older release --------------------------------------------------------------------------------------------

    /** A release without the columns (core-v11) stages and serves the three keys as null (DEVIATIONS). */
    @Test
    void aReleaseWithoutTheColumnsServesNull() {
        for (String f : NEW_FIELDS) {
            assertThat(v11Comparison.has(f)).as(f).isTrue();
            assertThat(v11Comparison.get(f).isNull()).as(f).isTrue();
            for (JsonNode m : v11Calendar.get("months")) {
                assertThat(m.get(f).isNull()).as(f).isTrue();
            }
        }
        // the baseline list stays 2001–2025 without the column
        assertThat(years(v11Comparison)).containsExactlyElementsOf(range(2001, 2025, 2024));
    }

    // ---- helpers --------------------------------------------------------------------------------------------------

    /** verdict, baseline_period, continuity_ratio, continuity_source, percentile, n_baseline_years of the fixture row. */
    static List<Object> fileRow(String aoi, int year, int month) {
        List<List<Object>> rows = Duck.query("SELECT verdict, baseline_period, continuity_ratio, continuity_source, percentile, "
                + "CAST(n_baseline_years AS INTEGER) FROM read_parquet(?) WHERE aoi_id = ? AND year = ? AND month = ?", COMPARE, aoi, year, month);
        assertThat(rows).hasSize(1);
        return rows.get(0);
    }

    void assertComparisonIsTheFileRow(String aoi, int year, int month) {
        List<Object> file = fileRow(aoi, year, month);
        JsonNode c = ok("/api/v1/aois/" + aoi + "/months/" + year + "-" + String.format("%02d", month) + "/comparison");
        assertThat(c.get("verdict").asString()).isEqualTo(file.get(0));
        assertThat(c.get("baseline_period").asString()).isEqualTo(file.get(1));
        assertThat(c.get("continuity_ratio").asDouble()).isEqualTo(((Number) file.get(2)).doubleValue());
        assertThat(c.get("continuity_source").asString()).isEqualTo(file.get(3));
        assertThat(c.get("n_baseline_years").asInt()).isEqualTo(file.get(5));
        JsonNode m = calendarMonth(ok("/api/v1/aois/" + aoi + "/calendar?start_year=" + year + "&end_year=" + year), month);
        assertThat(m.get("verdict").asString()).isEqualTo(file.get(0));
        for (String f : NEW_FIELDS) {
            assertThat(m.get(f)).as(f).isEqualTo(c.get(f));
        }
    }

    static JsonNode calendarMonth(JsonNode calendar, int month) {
        for (JsonNode m : calendar.get("months")) {
            if (m.get("month").asInt() == month) {
                return m;
            }
        }
        throw new AssertionError("no month " + month);
    }

    static List<Integer> years(JsonNode comparison) {
        List<Integer> out = new ArrayList<>();
        comparison.get("baseline").forEach(b -> out.add(b.get("year").asInt()));
        return out;
    }

    static long usedCount(JsonNode comparison) {
        long n = 0;
        for (JsonNode b : comparison.get("baseline")) {
            n += b.get("used").asBoolean() ? 1 : 0;
        }
        return n;
    }

    static List<Integer> range(int from, int to, int skip) {
        List<Integer> out = new ArrayList<>();
        for (int y = from; y <= to; y++) {
            if (y != skip) {
                out.add(y);
            }
        }
        return out;
    }

    JsonNode ok(String path) {
        HttpResponse<String> r = get(path);
        assertThat(r.statusCode()).as(path + " " + r.body()).isEqualTo(200);
        return json(r);
    }

    HttpResponse<String> get(String path) {
        try {
            return HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).timeout(Duration.ofSeconds(30)).GET()
                    .build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    static JsonNode json(HttpResponse<String> r) {
        return JSON.readTree(Objects.requireNonNull(r.body()));
    }
}

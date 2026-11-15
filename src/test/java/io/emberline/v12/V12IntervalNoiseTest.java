package io.emberline.v12;

import static org.assertj.core.api.Assertions.assertThat;

import io.emberline.platform.flyway.MultiSchemaFlywayRunner;
import io.emberline.shared.release.load.cases.DataCase;
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
import java.util.List;
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
 * pl-v12b (v1.2-2026-11-15-r1 rejected with INTERVAL_INVERTED on float noise): value_lo above value_hi by at most
 * {@link DataCase#INTERVAL_NOISE_TOLERANCE} is kept with a warning and served ordered; more still rejects. Releases over
 * owner fixture core-v11: {@code noise} (one cell month lo-hi = 5e-6, one KR aoi month lo-hi = 2e-6), {@code edge}
 * (one cell month lo-hi = exactly 1e-4), {@code wide} (one cell month lo-hi = 2e-4).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"management.server.port=0", "emberline.ratelimit.enabled=false", "emberline.release.poll-interval=1s"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class V12IntervalNoiseTest {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    static final Path CORE_V11 = Fixtures.releaseRoot().resolve("core-v11");
    /** a KR cell of 2025-03 with a band (V12ReleaseTest serves its neighbour through /cells?aoi_id=KR). */
    static final long[] CELLS = cellsNear(4549888L);
    static final long NOISE_CELL = CELLS[0];
    static final long EDGE_CELL = CELLS[1];
    static final int[] KR_MONTH = krBandMonth();
    static final Path ROOT = newRoot();

    @LocalServerPort
    int port;

    @Value("${spring.datasource.url}")
    String jdbcUrl;

    IngestCli.Result noise;
    IngestCli.Result edge;
    IngestCli.Result wide;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        String url = EmbeddedPg.newDatabase();
        MultiSchemaFlywayRunner.migrateAll(EmbeddedPg.dataSource(url));
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("emberline.release.root", ROOT::toString);
    }

    static long[] cellsNear(long cell) {
        List<String> ids = column("SELECT cell_id FROM read_parquet('" + CORE_V11.resolve("harmonized_cell_month.parquet") + "') "
                + "WHERE year = 2025 AND month = 3 AND value IS NOT NULL AND value_lo < value_hi AND value BETWEEN value_lo AND value_hi "
                + "AND cell_id <> " + cell + " ORDER BY abs(cell_id - " + cell + "), cell_id LIMIT 2");
        assertThat(ids).hasSize(2);
        return new long[] {Long.parseLong(ids.get(0)), Long.parseLong(ids.get(1))};
    }

    static int[] krBandMonth() {
        List<String> ym = column("SELECT year * 100 + month FROM read_parquet('" + CORE_V11.resolve("aoi_month.parquet") + "') "
                + "WHERE aoi_id = 'KR' AND status = 'ok' AND value IS NOT NULL AND value_lo < value - 1 AND value_hi > value + 1 "
                + "ORDER BY year, month LIMIT 1");
        assertThat(ym).hasSize(1);
        int v = Integer.parseInt(ym.get(0));
        return new int[] {v / 100, v % 100};
    }

    static List<String> column(String sql) {
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            List<String> out = new ArrayList<>();
            while (rs.next()) {
                out.add(rs.getString(1));
            }
            return out;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** CASE that sets one column of one cell month (2025-03) to {@code expr} and keeps every other row. */
    static String cell(long cellId, String column, String expr) {
        return "CASE WHEN cell_id = " + cellId + " AND year = 2025 AND month = 3 THEN " + expr + " ELSE " + column + " END AS " + column;
    }

    static Path newRoot() {
        try {
            Path root = Files.createDirectory(Files.createTempDirectory("emberline-v12b").resolve("releases"));
            Path noise = copy(root.resolve("noise"));
            rewrite(noise, "harmonized_cell_month.parquet", "SELECT * REPLACE ("
                    + cell(NOISE_CELL, "value_lo", "value + 2.5e-6") + ", " + cell(NOISE_CELL, "value_hi", "value - 2.5e-6") + ") FROM src");
            String kr = "aoi_id = 'KR' AND year = " + KR_MONTH[0] + " AND month = " + KR_MONTH[1];
            rewrite(noise, "aoi_month.parquet", "SELECT * REPLACE ("
                    + "CASE WHEN " + kr + " THEN value + 1e-6 ELSE value_lo END AS value_lo, "
                    + "CASE WHEN " + kr + " THEN value - 1e-6 ELSE value_hi END AS value_hi) FROM src");
            resum(noise);
            Path edge = copy(root.resolve("edge"));
            rewrite(edge, "harmonized_cell_month.parquet", "SELECT * REPLACE (" + cell(EDGE_CELL, "value", "0.0::DOUBLE") + ", "
                    + cell(EDGE_CELL, "value_lo", "0.0001::DOUBLE") + ", " + cell(EDGE_CELL, "value_hi", "0.0::DOUBLE") + ") FROM src");
            resum(edge);
            Path wide = copy(root.resolve("wide"));
            rewrite(wide, "harmonized_cell_month.parquet", "SELECT * REPLACE ("
                    + cell(NOISE_CELL, "value_lo", "value + 2e-4") + ", " + cell(NOISE_CELL, "value_hi", "value") + ") FROM src");
            resum(wide);
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
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE src AS SELECT * FROM read_parquet('" + tmp + "')");
            s.execute("COPY (" + selectOverSrc + ") TO '" + dir.resolve(file) + "' (FORMAT parquet)");
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        Files.delete(tmp);
    }

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
        noise = IngestCli.run(jdbcUrl, ROOT, "stage", "noise");
        edge = IngestCli.run(jdbcUrl, ROOT, "stage", "edge");
        wide = IngestCli.run(jdbcUrl, ROOT, "stage", "wide");
        assertThat(noise.exit()).as(noise.json().toString()).isZero();
        assertThat(IngestCli.run(jdbcUrl, ROOT, "activate", "noise", "--expect-sha256", Fixtures.sha256(ROOT.resolve("noise/SHA256SUMS"))).exit()).isZero();
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

    static List<String> warnings(IngestCli.Result r) {
        List<String> out = new ArrayList<>();
        r.json().get("warnings").forEach(n -> out.add(n.asString()));
        return out;
    }

    String scalar(String sql) throws SQLException {
        try (Connection c = EmbeddedPg.dataSource(jdbcUrl).getConnection(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    String storedCell(String key, long cellId, String column) throws SQLException {
        return scalar("SELECT " + column + " FROM observation.cell_month WHERE release_id = (SELECT id FROM ingest.release WHERE release_key = '"
                + key + "') AND cell_id = " + cellId + " AND year = 2025 AND month = 3");
    }

    @Test
    void noiseInversionOf5em6StagesWithTheWarningAndLoadsTheRowAsStored() throws SQLException {
        assertThat(noise.reasons()).isEmpty();
        assertThat(warnings(noise)).contains(
                "INTERVAL_INVERTED_NOISE:harmonized_cell_month.parquet:1 rows kept as stored (lo-hi <= 1e-4)",
                "INTERVAL_INVERTED_NOISE:aoi_month.parquet:1 rows kept as stored (lo-hi <= 1e-4)");
        double lo = Double.parseDouble(storedCell("noise", NOISE_CELL, "value_lo"));
        double hi = Double.parseDouble(storedCell("noise", NOISE_CELL, "value_hi"));
        assertThat(lo - hi).as("stored inverted, A1").isBetween(4.9e-6, 5.1e-6);
    }

    @Test
    void inversionOfExactly1em4IsKept() throws SQLException {
        assertThat(edge.exit()).as(edge.json().toString()).isZero();
        assertThat(edge.reasons()).isEmpty();
        assertThat(warnings(edge)).contains("INTERVAL_INVERTED_NOISE:harmonized_cell_month.parquet:1 rows kept as stored (lo-hi <= 1e-4)");
        assertThat(Double.parseDouble(storedCell("edge", EDGE_CELL, "value_lo")) - Double.parseDouble(storedCell("edge", EDGE_CELL, "value_hi")))
                .isEqualTo(DataCase.INTERVAL_NOISE_TOLERANCE);
    }

    @Test
    void inversionOf2em4StillRejects() {
        assertThat(wide.exit()).as(wide.json().toString()).isEqualTo(3);
        assertThat(wide.reasons()).contains("INTERVAL_INVERTED:harmonized_cell_month.parquet:1 rows");
        assertThat(warnings(wide)).noneMatch(w -> w.startsWith("INTERVAL_INVERTED_NOISE:harmonized_cell_month.parquet"));
    }

    @Test
    void servedBandOfAKeptNoiseRowIsNotInverted() throws SQLException {
        JsonNode cells = json(get("/api/v1/cells?aoi_id=KR&month=2025-03&format=json"));
        JsonNode props = null;
        for (JsonNode f : cells.get("features")) {
            if (f.path("properties").path("cell_id").asLong() == NOISE_CELL) {
                props = f.get("properties");
            }
        }
        assertThat(props).as("cell " + NOISE_CELL + " served").isNotNull();
        assertThat(props.get("value_lo").doubleValue()).isLessThan(props.get("value_hi").doubleValue());
        assertThat(props.get("value_lo").doubleValue()).isEqualTo(Double.parseDouble(storedCell("noise", NOISE_CELL, "value_hi")));
        assertThat(props.get("value_hi").doubleValue()).isEqualTo(Double.parseDouble(storedCell("noise", NOISE_CELL, "value_lo")));
        assertThat(props.get("value").doubleValue()).isEqualTo(Double.parseDouble(storedCell("noise", NOISE_CELL, "value")));

        JsonNode cal = json(get("/api/v1/aois/KR/calendar?start_year=" + KR_MONTH[0] + "&end_year=" + KR_MONTH[0]));
        JsonNode month = null;
        for (JsonNode m : cal.get("months")) {
            if (m.get("month").asInt() == KR_MONTH[1]) {
                month = m;
            }
        }
        assertThat(month).as(cal.toString()).isNotNull();
        assertThat(month.get("value_lo").isNumber()).as(month.toString()).isTrue();
        assertThat(month.get("value_lo").doubleValue()).as(month.toString()).isLessThan(month.get("value_hi").doubleValue());
        assertThat(month.get("value").doubleValue()).isBetween(month.get("value_lo").doubleValue(), month.get("value_hi").doubleValue());
    }
}

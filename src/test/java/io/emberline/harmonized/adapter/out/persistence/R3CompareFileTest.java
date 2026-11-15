package io.emberline.harmonized.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import io.emberline.shared.config.EmberlineProperties;
import io.emberline.shared.release.load.DuckDb;
import io.emberline.shared.release.load.ParquetColumns;
import io.emberline.shared.release.load.ReleaseManifestCheck;
import io.emberline.shared.release.load.ValidationReport;
import io.emberline.shared.sql.SqlResource;
import io.emberline.support.Duck;
import io.emberline.support.Fixtures;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The real data-v1.2-2026-11-15 stage r3, read in place and never written: the replaced
 * aoi_month_compare.parquet and the release_manifest.csv with its three new reports/ entries. The folder is
 * {@code $EMBERLINE_R3_STAGE}, else {@code planetarium/release/v1.2-2026-11-15-r3} under the user's home; every test is
 * skipped (with that reason) where the stage is not on the machine. No fixture holds the whole file, so the counts by
 * baseline_period are pinned here.
 */
class R3CompareFileTest {

    static final Path R3 = Path.of(System.getenv().getOrDefault("EMBERLINE_R3_STAGE",
            Path.of(System.getProperty("user.home"), "planetarium", "release", "v1.2-2026-11-15-r3").toString()));
    static final Path FILE = R3.resolve(HarmonizedReleaseLoader.COMPARE);
    static final String SHA256 = "4b108682d98822dc010c25ce3834ef978951d0b812dba8b6dca60c8ce5be814b";
    static final List<String> NEW_REPORTS = List.of("reports/compare_continuity_ratios.csv",
            "reports/compare_continuity_validation.csv", "reports/projection_method_validation.csv");
    static final DuckDb DUCK = new DuckDb(new EmberlineProperties(null, null, null, null, null));

    @BeforeAll
    static void stagePresent() {
        Assumptions.assumeTrue(Files.isRegularFile(FILE) && Files.isRegularFile(R3.resolve(ReleaseManifestCheck.FILE)),
                "release stage v1.2-2026-11-15-r3 is not on this machine (set EMBERLINE_R3_STAGE to its folder)");
    }

    @Test
    void theFileIsTheAnnouncedOne() {
        assertThat(Fixtures.sha256(FILE)).isEqualTo(SHA256);
    }

    /** 1,580,963 rows; baseline_period 2001-2025 950,268 rows / 3,036 AOIs, 2012-2025 630,695 rows / 2,015 AOIs. */
    @Test
    void countsByBaselinePeriod() {
        assertThat(((Number) Duck.query("SELECT count(*) FROM read_parquet(?)", FILE).get(0).get(0)).longValue()).isEqualTo(1_580_963L);
        Map<String, long[]> by = new HashMap<>();
        for (List<Object> r : Duck.query("SELECT baseline_period, count(*), count(DISTINCT aoi_id) FROM read_parquet(?) GROUP BY 1", FILE)) {
            by.put((String) r.get(0), new long[] {((Number) r.get(1)).longValue(), ((Number) r.get(2)).longValue()});
        }
        assertThat(by).containsOnlyKeys("2001-2025", "2012-2025");
        assertThat(by.get("2001-2025")).containsExactly(950_268L, 3_036L);
        assertThat(by.get("2012-2025")).containsExactly(630_695L, 2_015L);
        // one value of each of the three columns per AOI; every source is own or parent:<aoi_id of aoi_list>
        assertThat(Duck.query("SELECT count(*) FROM (SELECT aoi_id FROM read_parquet(?) GROUP BY 1 HAVING count(DISTINCT baseline_period) > 1 "
                + "OR count(DISTINCT continuity_ratio) > 1 OR count(DISTINCT continuity_source) > 1)", FILE).get(0).get(0)).isEqualTo(0L);
        assertThat(Duck.query("SELECT count(*) FROM read_parquet(?) WHERE continuity_source IS NULL OR NOT (continuity_source = 'own' "
                + "OR substr(continuity_source, 8) IN (SELECT aoi_id FROM read_parquet(?)) AND continuity_source LIKE 'parent:%')",
                FILE, R3.resolve("aoi_list.parquet")).get(0).get(0)).isEqualTo(0L);
        // months before 2012 of 2012-2025 regions are uncertain in the file (model contract line 89)
        assertThat(Duck.query("SELECT count(*) FILTER (WHERE verdict <> 'uncertain'), count(*) FROM read_parquet(?) "
                + "WHERE baseline_period = '2012-2025' AND year < 2012", FILE).get(0)).containsExactly(0L, 270_010L);
    }

    /** The smoke rows of the request, in the real file. */
    @Test
    void smokeRowsInTheRealFile() {
        Map<String, List<Object>> rows = new HashMap<>();
        for (List<Object> r : Duck.query("SELECT aoi_id, verdict, baseline_period, continuity_ratio, continuity_source, "
                + "CAST(n_baseline_years AS INTEGER) FROM read_parquet(?) WHERE year = 2024 AND month = 8 AND aoi_id IN ('KR', 'ADM0_ARG', 'WORLD')", FILE)) {
            rows.put((String) r.get(0), r);
        }
        assertThat(rows.get("KR").subList(1, 3)).containsExactly("above", "2012-2025");
        assertThat(((Number) rows.get("KR").get(3)).doubleValue()).isCloseTo(0.6814, within(5e-5));
        assertThat(rows.get("KR").subList(4, 6)).containsExactly("own", 13);
        assertThat(rows.get("ADM0_ARG").subList(1, 3)).containsExactly("below", "2001-2025");
        assertThat(((Number) rows.get("ADM0_ARG").get(3)).doubleValue()).isCloseTo(1.0459, within(5e-5));
        assertThat(rows.get("WORLD").subList(1, 3)).containsExactly("above", "2001-2025");
        assertThat(((Number) rows.get("WORLD").get(3)).doubleValue()).isCloseTo(1.0175, within(5e-5));
    }

    /** The loader's own column check and SELECT read the three columns from the real file (no COLUMN_ABSENT, same counts). */
    @Test
    void theLoaderSelectCarriesTheColumns() throws SQLException {
        ValidationReport report = new ValidationReport();
        try (Connection duck = DUCK.open()) {
            ParquetColumns c = ParquetColumns.inspect(duck, FILE.toString(), HarmonizedReleaseLoader.COMPARE,
                    HarmonizedReleaseLoader.COMPARE_COLUMNS, report);
            assertThat(c.ok()).as(report.reasons().toString()).isTrue();
            assertThat(report.warnings()).noneMatch(w -> w.startsWith("COLUMN_ABSENT"));
            String select = c.render(SqlResource.text("harmonized/duck_aoi_month_compare.sql"));
            Map<String, Long> by = new HashMap<>();
            try (PreparedStatement ps = duck.prepareStatement("SELECT baseline_period, count(*), count(continuity_ratio), "
                    + "count(continuity_source) FROM (" + select + ") GROUP BY 1")) {
                ps.setString(1, FILE.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        assertThat(rs.getLong(3)).isEqualTo(rs.getLong(2));
                        assertThat(rs.getLong(4)).isEqualTo(rs.getLong(2));
                        by.put(rs.getString(1), rs.getLong(2));
                    }
                }
            }
            assertThat(by).isEqualTo(Map.of("2001-2025", 950_268L, "2012-2025", 630_695L));
        }
    }

    /**
     * ReleaseManifestCheck, unchanged, passes the r3 manifest: the three new reports/ entries are listed and present (only
     * the loader files get the bytes / sha256 / rows check — here just aoi_month_compare, so 3.5 GB is not hashed).
     */
    @Test
    void releaseManifestCheckAcceptsTheR3Manifest() throws IOException {
        List<String> lines = Files.readAllLines(R3.resolve(ReleaseManifestCheck.FILE));
        assertThat(lines).hasSize(90);
        for (String report : NEW_REPORTS) {
            assertThat(lines).anyMatch(l -> l.startsWith(report + ","));
            assertThat(R3.resolve(report)).isRegularFile();
        }
        assertThat(lines).contains("aoi_month_compare.parquet,1580963,29441512," + SHA256);
        ValidationReport report = new ValidationReport();
        new ReleaseManifestCheck(DUCK).check(R3, Set.of(HarmonizedReleaseLoader.COMPARE), Fixtures::sha256, report);
        assertThat(report.ok()).as(report.reasons().toString()).isTrue();
        assertThat(report.reasons()).isEmpty();
        assertThat(report.warnings()).noneMatch(w -> w.contains("reports/"));
    }
}

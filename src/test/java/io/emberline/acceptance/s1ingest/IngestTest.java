package io.emberline.acceptance.s1ingest;

import static org.assertj.core.api.Assertions.assertThat;

import io.emberline.platform.flyway.MultiSchemaFlywayRunner;
import io.emberline.support.Duck;
import io.emberline.support.EmbeddedPg;
import io.emberline.support.Fixtures;
import io.emberline.support.IngestCli;
import io.emberline.support.ReleaseFixtures;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/** the data rules through the ingest CLI wiring, one database, core active first. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IngestTest {

    String url;
    DataSource ds;
    Path root;

    @BeforeAll
    void activateCore() {
        url = EmbeddedPg.newDatabase();
        ds = EmbeddedPg.dataSource(url);
        MultiSchemaFlywayRunner.migrateAll(ds);
        root = ReleaseFixtures.newRoot();
        assertThat(IngestCli.run(url, root, "stage", "core").exit()).isZero();
        assertThat(IngestCli.run(url, root, "activate", "core", "--expect-sha256", Fixtures.coreManifestSha256()).exit()).isZero();
    }

    String activeKey() throws Exception {
        return scalar("SELECT r.release_key FROM ingest.active_release a JOIN ingest.release r ON r.id = a.release_id");
    }

    String scalar(String sql) throws Exception {
        try (Connection c = ds.getConnection(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    @Test
    void b1_02_brokenReleasesExit3WithReasonAndKeepTheActiveRelease() throws Exception {
        Path missingCol = ReleaseFixtures.copyCore(root, "broken-missing-col");
        ReleaseFixtures.rewrite(missingCol, "aoi_list.parquet", "SELECT * EXCLUDE (hidden) FROM src");
        Path dupKey = ReleaseFixtures.copyCore(root, "broken-dupkey");
        ReleaseFixtures.rewrite(dupKey, "aoi_month.parquet", "SELECT * FROM src UNION ALL (SELECT * FROM src WHERE aoi_id = 'KR' AND year = 2025 AND month = 3)");
        Path badEnum = ReleaseFixtures.copyCore(root, "broken-enum");
        ReleaseFixtures.rewrite(badEnum, "aoi_month.parquet",
                "SELECT * REPLACE (CASE WHEN aoi_id = 'KR' AND year = 2025 AND month = 3 THEN 'bogus' ELSE status END AS status) FROM src");
        Path badHash = ReleaseFixtures.copyCore(root, "broken-hash");
        Files.writeString(badHash.resolve("m2v_meta.json"), Files.readString(badHash.resolve("m2v_meta.json")) + " ");

        record Case(String key, String reasonPrefix) {
        }
        for (Case c : List.of(new Case("broken-missing-col", "COLUMN_MISSING:aoi_list.parquet:hidden"),
                new Case("broken-dupkey", "DUPLICATE_KEY:aoi_month.parquet"),
                new Case("broken-enum", "CODE_VALUE:aoi_month.parquet:status=bogus"),
                new Case("broken-hash", "FILE_HASH_MISMATCH:m2v_meta.json"))) {
            IngestCli.Result r = IngestCli.run(url, root, "stage", c.key());
            assertThat(r.exit()).as(c.key()).isEqualTo(3);
            assertThat(r.state()).isEqualTo("REJECTED");
            assertThat(r.reasons()).as(c.key()).anyMatch(reason -> reason.startsWith(c.reasonPrefix()));
            assertThat(activeKey()).isEqualTo("core");
            String id = scalar("SELECT id FROM ingest.release WHERE release_key = '" + c.key() + "'");
            assertThat(scalar("SELECT count(*) FROM pg_tables WHERE tablename LIKE '%\\_r" + id + "'")).as("no partitions left by " + c.key()).isEqualTo("0");
        }
    }

    @Test
    void b1_03_hashMismatchOnActivateRejectsAndKeepsActive() throws Exception {
        ReleaseFixtures.copyCore(root, "core-hash");
        assertThat(IngestCli.run(url, root, "stage", "core-hash").exit()).isZero();
        IngestCli.Result r = IngestCli.run(url, root, "activate", "core-hash", "--expect-sha256", "0".repeat(64));
        assertThat(r.exit()).isEqualTo(4);
        assertThat(r.state()).isEqualTo("REJECTED");
        assertThat(r.reasons()).contains("RELEASE_HASH_MISMATCH");
        assertThat(IngestCli.run(url, root, "show", "core-hash").state()).isEqualTo("REJECTED");
        assertThat(activeKey()).isEqualTo("core");
        assertThat(IngestCli.run(url, root, "activate", "core", "--expect-sha256", "not-hex").exit()).isEqualTo(3);
        assertThat(IngestCli.run(url, root, "activate", "core").exit()).isEqualTo(3);
        assertThat(activeKey()).isEqualTo("core");
    }

    @Test
    void b1_04_hostileKeysExit3AndTouchNothing() throws Exception {
        Set<String> before = tree(root.getParent());
        for (String key : List.of("../evil", "EVIL", "a".repeat(65), "..", "core/../core", "/etc", "a b")) {
            IngestCli.Result r = IngestCli.run(url, root, "stage", key);
            assertThat(r.exit()).as(key).isEqualTo(3);
        }
        assertThat(tree(root.getParent())).isEqualTo(before);
        assertThat(scalar("SELECT count(*) FROM ingest.release WHERE release_key NOT LIKE 'core%' AND release_key NOT LIKE 'broken%' AND release_key <> 'v0names'")).isEqualTo("0");
    }

    /** Rows as text of exact values (doubles and floats by their shortest round-trip form). */
    List<String> column(String sql) throws Exception {
        List<String> out = new java.util.ArrayList<>();
        try (Connection c = ds.getConnection(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            int n = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                StringBuilder row = new StringBuilder();
                for (int i = 1; i <= n; i++) {
                    Object o = rs.getObject(i);
                    row.append(o instanceof Float f ? Float.toString(f) : o instanceof Double d ? Double.toString(d) : String.valueOf(o)).append('|');
                }
                out.add(row.toString());
            }
        }
        return out;
    }

    static List<String> duckColumn(String sql, Path file) {
        return Duck.query(sql, file).stream().map(r -> {
            StringBuilder row = new StringBuilder();
            for (Object o : r) {
                row.append(o instanceof Float f ? Float.toString(f) : o instanceof Double d ? Double.toString(d) : String.valueOf(o)).append('|');
            }
            return row.toString();
        }).toList();
    }

    static Set<String> tree(Path dir) throws IOException {
        try (Stream<Path> s = Files.walk(dir, 2)) {
            return s.map(Path::toString).collect(java.util.stream.Collectors.toCollection(TreeSet::new));
        }
    }

    @Test
    void b1_05_v0NamesAreTranslatedAndReported() throws Exception {
        Path v0 = ReleaseFixtures.copyCore(root, "v0names");
        String rename = "SELECT * REPLACE (CASE value_source WHEN 'modis_m2v' THEN 'modis_harmonized' WHEN 'n20_bridge' THEN 'noaa20_bridged' "
                + "WHEN 'modis_m2v_terra' THEN 'modis_harmonized_terra_only' ELSE value_source END AS value_source) FROM src";
        ReleaseFixtures.rewrite(v0, "aoi_month.parquet", rename);
        ReleaseFixtures.rewrite(v0, "harmonized_cell_month.parquet", rename);
        IngestCli.Result r = IngestCli.run(url, root, "stage", "v0names");
        assertThat(r.exit()).as(r.json().toString()).isZero();
        assertThat(r.aliases()).contains("modis_harmonized->modis_m2v", "noaa20_bridged->n20_bridge");
        String id = scalar("SELECT id FROM ingest.release WHERE release_key = 'v0names'");
        assertThat(scalar("SELECT count(*) FROM harmonized.aoi_month WHERE release_id = " + id
                + " AND value_source NOT IN ('modis_m2v_terra','modis_m2v','snpp_observed','n20_bridge','n21_bridge')")).isEqualTo("0");
        assertThat(scalar("SELECT count(*) FROM observation.cell_month WHERE release_id = " + id + " AND value_source = 'modis_m2v'"))
                .isEqualTo(String.valueOf(Duck.query("SELECT count(*) FROM read_parquet(?) WHERE value_source = 'modis_m2v'",
                        Duck.core("harmonized_cell_month.parquet")).get(0).get(0)));
    }

    @Test
    void b1_06_secondStageWhileLoadingExits5() throws Exception {
        ReleaseFixtures.copyCore(root, "core-busy");
        try (Connection holder = ds.getConnection(); Statement s = holder.createStatement()) {
            s.executeQuery("SELECT pg_try_advisory_lock(hashtext('emberline.ingest'))").close();
            s.execute("INSERT INTO ingest.release (release_key, state, staged_at, updated_at) VALUES ('core-loading', 'LOADING', now(), now())");
            IngestCli.Result r = IngestCli.run(url, root, "stage", "core-busy");
            assertThat(r.exit()).isEqualTo(5);
            assertThat(r.reasons()).anyMatch(x -> x.startsWith("RELEASE_STATE_CONFLICT"));
            s.execute("SELECT pg_advisory_unlock(hashtext('emberline.ingest'))");
        }
        IngestCli.Result after = IngestCli.run(url, root, "stage", "core-busy");
        assertThat(after.exit()).as("stale LOADING row of a dead loader is rejected, the new stage proceeds").isZero();
        assertThat(IngestCli.run(url, root, "show", "core-loading").state()).isEqualTo("REJECTED");
    }

    @Test
    void valuesAreStoredAsInTheFiles() throws Exception {
        String id = scalar("SELECT id FROM ingest.release WHERE release_key = 'core'");
        // no recomputation — every stored value equals the file's value bit for bit
        assertThat(column("SELECT value, value_lo, value_hi FROM harmonized.aoi_month WHERE release_id = " + id + " ORDER BY aoi_id, year, month"))
                .isEqualTo(duckColumn("SELECT value, value_lo, value_hi FROM read_parquet(?) ORDER BY aoi_id, year, month", Duck.core("aoi_month.parquet")));
        assertThat(column("SELECT percentile, ratio_to_median, baseline_median FROM harmonized.aoi_month_compare WHERE release_id = " + id + " ORDER BY aoi_id, year, month"))
                .isEqualTo(duckColumn("SELECT percentile, ratio_to_median, baseline_median FROM read_parquet(?) ORDER BY aoi_id, year, month", Duck.core("aoi_month_compare.parquet")));
        assertThat(column("SELECT value, value_lo, value_hi, cell_lat, cell_lon FROM observation.cell_month WHERE release_id = " + id + " ORDER BY year, month, cell_id"))
                .isEqualTo(duckColumn("SELECT value, value_lo, value_hi, cell_lat, cell_lon FROM read_parquet(?) ORDER BY year, month, cell_id", Duck.core("harmonized_cell_month.parquet")));
        // the missing month keeps NULL with status missing — never 0
        assertThat(scalar("SELECT count(*) FROM harmonized.aoi_month WHERE release_id = " + id + " AND year = 2001 AND month = 6 AND value IS NULL AND status = 'missing'"))
                .isEqualTo(String.valueOf(Duck.query("SELECT count(*) FROM read_parquet(?) WHERE year = 2001 AND month = 6", Duck.core("aoi_month.parquet")).get(0).get(0)));
        assertThat(scalar("SELECT count(*) FROM harmonized.aoi_month WHERE release_id = " + id + " AND status = 'missing' AND value = 0")).isEqualTo("0");
        // out-of-interval cell values are kept as they are
        assertThat(scalar("SELECT count(*) FROM observation.cell_month WHERE release_id = " + id + " AND (value < value_lo OR value > value_hi)"))
                .isEqualTo(String.valueOf(Duck.query("SELECT count(*) FROM read_parquet(?) WHERE value < value_lo OR value > value_hi",
                        Duck.core("harmonized_cell_month.parquet")).get(0).get(0)));
        // KR 2025-03 (INSPECT §5): value 2764, compare above / percentile 100 / ratio 10.924901185770752
        assertThat(scalar("SELECT value FROM harmonized.aoi_month WHERE release_id = " + id + " AND aoi_id = 'KR' AND year = 2025 AND month = 3")).isEqualTo("2764");
        assertThat(scalar("SELECT verdict || ' ' || percentile || ' ' || ratio_to_median FROM harmonized.aoi_month_compare WHERE release_id = " + id
                + " AND aoi_id = 'KR' AND year = 2025 AND month = 3")).isEqualTo("above 100 10.924901185770752");
        // partitions are attached children of LIST-partitioned parents (9 = 's 8 + harmonized.cell_center)
        assertThat(scalar("SELECT count(*) FROM pg_inherits i JOIN pg_class c ON c.oid = i.inhrelid WHERE c.relname LIKE '%\\_r" + id + "'")).isEqualTo("9");
    }
}

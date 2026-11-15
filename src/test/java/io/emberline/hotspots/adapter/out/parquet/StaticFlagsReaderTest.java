package io.emberline.hotspots.adapter.out.parquet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import io.emberline.hotspots.application.service.HotspotSettings;
import io.emberline.hotspots.domain.BboxArea;
import io.emberline.hotspots.domain.Hotspot;
import io.emberline.hotspots.domain.HotspotScan;
import io.emberline.hotspots.domain.Layer;
import io.emberline.hotspots.domain.Totals;
import io.emberline.shared.config.EmberlineProperties;
import io.emberline.support.Duck;
import io.emberline.support.Fixtures;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * pl-v12 item 2: hotspot page, totals and weights leave out points that match a v1.2
 * harmonized_hotspots_static_flags row with is_static_source = true (exact key equality). Points: owner fixture
 * core-v11 reference 2026-01 (real values) plus one derived point near the year boundary (UTC 2025-12-31, local
 * 2026-01-01) in the year=2025/month=12 folder. Release "flagged" has the flag folder, release "plain" the same points
 * without it. Expected values come from DuckDB over the parquet files, not from the reader.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StaticFlagsReaderTest {

    static final Path SRC = Fixtures.releaseRoot().resolve("core-v11/harmonized_hotspots/reference/year=2026/month=01/part-0.parquet");
    static final BboxArea KR = new BboxArea(124, 33, 132, 39);
    static final LocalDate START = LocalDate.of(2025, 12, 15);
    static final LocalDate END = LocalDate.of(2026, 1, 31);

    Path root;
    HotspotParquetReader reader;
    /** acq µs|lat|lon|satellite of the points whose flag is_static_source = true (3 regular + the boundary point). */
    Set<String> flagged = new HashSet<>();
    String nearMissKey;
    String notStaticKey;

    @BeforeAll
    void build() throws Exception {
        root = Files.createDirectory(Files.createTempDirectory("emberline-v12-flags").resolve("releases"));
        for (String rel : List.of("flagged", "plain")) {
            Path jan = Files.createDirectories(root.resolve(rel + "/harmonized_hotspots/reference/year=2026/month=01"));
            Files.copy(SRC, jan.resolve("part-0.parquet"));
            Path dec = Files.createDirectories(root.resolve(rel + "/harmonized_hotspots/reference/year=2025/month=12"));
            duck("COPY (SELECT * REPLACE (DATE '2026-01-01' AS date_local, TIMESTAMPTZ '2025-12-31 16:32:00+00' AS acq_datetime_utc) "
                    + "FROM read_parquet('" + SRC + "', hive_partitioning = false) WHERE is_reference AND latitude BETWEEN 33 AND 39 AND longitude BETWEEN 124 AND 132 "
                    + "ORDER BY acq_datetime_utc DESC, latitude, longitude LIMIT 1) TO '"
                    + dec.resolve("part-0.parquet") + "' (FORMAT parquet)");
        }
        Path flags = Files.createDirectories(root.resolve("flagged/harmonized_hotspots_static_flags"));
        String ordered = "SELECT row_number() OVER (ORDER BY acq_datetime_utc, latitude, longitude, satellite) AS rn, * "
                + "FROM read_parquet('" + SRC + "', hive_partitioning = false) WHERE is_reference AND latitude BETWEEN 33 AND 39 AND longitude BETWEEN 124 AND 132";
        // rn 1-3 static; rn 4 differs from its point only in latitude (+0.0001) → point kept; rn 5 is_static_source = false → kept
        String flagRows = "SELECT satellite, CASE WHEN rn = 4 THEN latitude + 0.0001 ELSE latitude END AS latitude, longitude, acq_datetime_utc, "
                + "date_local, cell_id, 'reference' AS layer, source, is_reference, harmonized_weight, rn <> 5 AS is_static_source "
                + "FROM (" + ordered + ") WHERE rn <= 5";
        duck("COPY (" + flagRows + ") TO '" + flags.resolve("year=2026.parquet") + "' (FORMAT parquet)");
        Path decPoint = root.resolve("flagged/harmonized_hotspots/reference/year=2025/month=12/part-0.parquet");
        duck("COPY (SELECT * FROM read_parquet('" + flags.resolve("year=2026.parquet") + "') UNION ALL "
                + "SELECT satellite, latitude, longitude, acq_datetime_utc, date_local, cell_id, 'reference', source, is_reference, harmonized_weight, true "
                + "FROM read_parquet('" + decPoint + "', hive_partitioning = false)) TO '" + flags.resolve("year=2026.tmp") + "' (FORMAT parquet)");
        Files.move(flags.resolve("year=2026.tmp"), flags.resolve("year=2026.parquet"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        // a flag file for a year no scan here touches: never read
        duck("COPY (SELECT * FROM read_parquet('" + flags.resolve("year=2026.parquet") + "') LIMIT 0) TO '" + flags.resolve("year=2030.parquet") + "' (FORMAT parquet)");

        for (List<Object> r : Duck.query("SELECT epoch_us(acq_datetime_utc), latitude, longitude, satellite, is_static_source FROM read_parquet(?) ORDER BY 1",
                flags.resolve("year=2026.parquet"))) {
            if (Boolean.TRUE.equals(r.get(4))) {
                flagged.add(r.get(0) + "|" + r.get(1) + "|" + r.get(2) + "|" + r.get(3));
            }
        }
        List<List<Object>> four = Duck.query("SELECT epoch_us(acq_datetime_utc), latitude, longitude, satellite FROM (" + ordered + ") WHERE rn IN (4, 5) ORDER BY rn");
        nearMissKey = key(four.get(0));
        notStaticKey = key(four.get(1));
        flagged.remove(epochKey(four.get(0), 0.0001));

        reader = new HotspotParquetReader(new EmberlineProperties(null, null, null,
                new EmberlineProperties.Release(root.toString(), null), null), new HotspotSettings(100, 92, 31, 2, "256MB"));
    }

    @AfterAll
    void close() throws SQLException {
        reader.close();
    }

    static void duck(String sql) throws SQLException {
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:")) {
            c.createStatement().execute(sql);
        }
    }

    static String key(List<Object> r) {
        return r.get(0) + "|" + r.get(1) + "|" + r.get(2) + "|" + r.get(3);
    }

    static String epochKey(List<Object> r, double latShift) {
        return r.get(0) + "|" + (((Number) r.get(1)).doubleValue() + latShift) + "|" + r.get(2) + "|" + r.get(3);
    }

    static String key(Hotspot h) {
        long us = java.time.temporal.ChronoUnit.MICROS.between(Instant.EPOCH, h.acqDatetimeUtc());
        return us + "|" + h.latitude() + "|" + h.longitude() + "|" + h.satellite();
    }

    HotspotScan scan(String rel, Optional<io.emberline.hotspots.domain.PointFilter> filter) {
        List<Path> files = reader.files(rel, Layer.REFERENCE, YearMonth.from(START), YearMonth.from(END)).orElseThrow();
        // the service's call: date_local years of the scan plus the neighbouring year at each end
        List<Path> flags = reader.staticFlagFiles(rel, START.getYear() - 1, END.getYear() + 1);
        return new HotspotScan(files, START, END, KR, filter, flags);
    }

    /** Count / reference sum over every point file of the release in the scan, minus the given keys. */
    List<Object> expected(String rel, Set<String> excluded) {
        String where = "WHERE date_local BETWEEN DATE '2025-12-15' AND DATE '2026-01-31' AND latitude BETWEEN 33 AND 39 AND longitude BETWEEN 124 AND 132";
        long total = 0;
        double value = 0;
        long n = 0;
        for (List<Object> r : Duck.query("SELECT epoch_us(acq_datetime_utc), latitude, longitude, satellite, is_reference, harmonized_weight::DOUBLE "
                + "FROM read_parquet(?, hive_partitioning = false, union_by_name = true) " + where,
                root.resolve(rel + "/harmonized_hotspots/reference/*/*/*.parquet").toString())) {
            if (excluded.contains(key(r))) {
                continue;
            }
            total++;
            if (Boolean.TRUE.equals(r.get(4))) {
                n++;
                value += ((Number) r.get(5)).doubleValue();
            }
        }
        return List.of(total, value, n);
    }

    @Test
    void flaggedPointsAreAbsentFromPageTotalsAndWeights() {
        assertThat(flagged).hasSize(4);
        List<Object> exp = expected("flagged", flagged);
        List<Object> all = expected("flagged", Set.of());
        assertThat((long) all.get(0) - (long) exp.get(0)).as("3 regular flagged points + the boundary point").isEqualTo(4);

        Totals sql = reader.totals(scan("flagged", Optional.empty()));
        assertThat(sql.total()).isEqualTo((long) exp.get(0));
        assertThat(sql.nPoints()).isEqualTo((long) exp.get(2));
        assertThat(sql.value()).isCloseTo((double) exp.get(1), within(1e-3));

        Totals weights = reader.totals(scan("flagged", Optional.of((lat, lon) -> true)));
        assertThat(weights.total()).isEqualTo((long) exp.get(0));
        assertThat(weights.nPoints()).isEqualTo((long) exp.get(2));
        assertThat(weights.value()).isCloseTo((double) exp.get(1), within(1e-3));

        List<Hotspot> page = reader.page(scan("flagged", Optional.empty()), Optional.empty(), 100_000);
        assertThat(page).hasSize((int) (long) exp.get(0));
        Set<String> keys = new HashSet<>();
        page.forEach(h -> keys.add(key(h)));
        assertThat(keys).doesNotContainAnyElementsOf(flagged);
        assertThat(keys).as("differs from its flag row only in latitude").contains(nearMissKey);
        assertThat(keys).as("flag row with is_static_source = false").contains(notStaticKey);
        List<Hotspot> filtered = reader.page(scan("flagged", Optional.of((lat, lon) -> true)), Optional.empty(), 100_000);
        assertThat(filtered).hasSize(page.size());
    }

    @Test
    void yearBoundaryPointIsRemovedByTheNextYearsFlagFile() {
        String boundary = Duck.query("SELECT epoch_us(acq_datetime_utc), latitude, longitude, satellite FROM read_parquet(?, hive_partitioning = false)",
                root.resolve("flagged/harmonized_hotspots/reference/year=2025/month=12/part-0.parquet")).stream().map(StaticFlagsReaderTest::key).findFirst().orElseThrow();
        assertThat(flagged).contains(boundary);
        assertThat(reader.staticFlagFiles("flagged", 2024, 2027)).extracting(p -> p.getFileName().toString()).containsExactly("year=2026.parquet");
        // the point lies in the UTC-2025 folder; its flag is in year=2026 (local date 2026-01-01)
        HotspotScan dec = new HotspotScan(reader.files("flagged", Layer.REFERENCE, YearMonth.of(2025, 12), YearMonth.of(2025, 12)).orElseThrow(),
                LocalDate.of(2025, 12, 31), LocalDate.of(2026, 1, 1), KR, Optional.empty(), reader.staticFlagFiles("flagged", 2024, 2027));
        assertThat(reader.page(dec, Optional.empty(), 10)).isEmpty();
        assertThat(reader.totals(dec).total()).isZero();
        HotspotScan noFlags = new HotspotScan(dec.files(), dec.start(), dec.end(), KR, Optional.empty());
        assertThat(reader.page(noFlags, Optional.empty(), 10)).extracting(StaticFlagsReaderTest::key).containsExactly(boundary);
    }

    @Test
    void withoutTheFlagFolderEveryPointIsServedAsBefore() {
        assertThat(reader.staticFlagFiles("plain", 2000, 2030)).isEmpty();
        List<Object> exp = expected("plain", Set.of());
        Totals sql = reader.totals(scan("plain", Optional.empty()));
        assertThat(sql.total()).isEqualTo((long) exp.get(0));
        assertThat(sql.nPoints()).isEqualTo((long) exp.get(2));
        assertThat(sql.value()).isCloseTo((double) exp.get(1), within(1e-3));
        Totals weights = reader.totals(scan("plain", Optional.of((lat, lon) -> true)));
        assertThat(weights.total()).isEqualTo((long) exp.get(0));
        assertThat(weights.value()).isCloseTo((double) exp.get(1), within(1e-3));
        assertThat(reader.page(scan("plain", Optional.empty()), Optional.empty(), 100_000)).hasSize((int) (long) exp.get(0));
        // the same scan built the 62779c6 way (no flag list) gives the same answer
        HotspotScan old = new HotspotScan(scan("plain", Optional.empty()).files(), START, END, KR, Optional.empty());
        assertThat(reader.totals(old)).isEqualTo(sql);
    }
}

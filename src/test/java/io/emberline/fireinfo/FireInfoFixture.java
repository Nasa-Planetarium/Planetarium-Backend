package io.emberline.fireinfo;

import io.emberline.support.Duck;
import io.emberline.support.Fixtures;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Release folders for the tests and the fixture rows they are checked against. {@code v12layers} = owner fixture core-v11
 * (stages as before) with the v1.2 aoi_list / aoi_boundaries and the five fire-info files of owner fixture core-v12-layers
 * (real values, see build_fixture_v12_layers.py); {@code v12layers-nocol} = the same with fire_events lacking {@code sgg_all}.
 * Expected values are read from the fixture parquet with DuckDB, never from our code.
 */
final class FireInfoFixture {

    static final Path CORE_V11 = Fixtures.releaseRoot().resolve("core-v11");
    static final Path V12_LAYERS = Fixtures.releaseRoot().resolve("core-v12-layers");
    static final List<String> LAYER_FILES = List.of("fire_events.parquet", "fwi_aoi_day.parquet", "fwi_clim_aoi_month.parquet",
            "spread_nowcast_latest.parquet", "aoi_month_fire_regime.parquet");
    static final Path EVENTS = V12_LAYERS.resolve("fire_events.parquet");
    static final Path FWI = V12_LAYERS.resolve("fwi_aoi_day.parquet");
    static final Path CLIM = V12_LAYERS.resolve("fwi_clim_aoi_month.parquet");
    static final Path SPREAD = V12_LAYERS.resolve("spread_nowcast_latest.parquet");
    static final Path REGIME = V12_LAYERS.resolve("aoi_month_fire_regime.parquet");
    static final Path AOI_LIST = V12_LAYERS.resolve("aoi_list.parquet");
    static final Path BOUNDARIES = V12_LAYERS.resolve("aoi_boundaries.geojson");

    /** One fire event as the fixture stores it (FLOAT stays Float, DOUBLE Double). */
    record Ev(String id, String region, String start, String end, long nDet, Float sumWeight, Double burned, String country,
              String adm1, String sido, String sggAll, double lat, double lon) {
    }

    /** An aoi_list SGG with its parent SIDO's name. */
    record Sgg(String id, String name, String sidoName) {
    }

    private static List<Ev> events;

    private FireInfoFixture() {
    }

    static Path newRoot() {
        try {
            Path root = Files.createDirectory(Files.createTempDirectory("emberline-fireinfo").resolve("releases"));
            release(root.resolve("v12layers"));
            Path nocol = release(root.resolve("v12layers-nocol"));
            duck("CREATE TABLE src AS SELECT * FROM read_parquet('" + EVENTS + "')",
                    "COPY (SELECT * EXCLUDE (sgg_all) FROM src) TO '" + nocol.resolve("fire_events.parquet") + "' (FORMAT parquet)");
            resum(root.resolve("v12layers"));
            resum(nocol);
            return root;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path release(Path dir) throws IOException {
        Files.createDirectories(dir);
        try (Stream<Path> files = Files.list(CORE_V11)) {
            for (Path f : files.filter(Files::isRegularFile).filter(f -> !f.getFileName().toString().equals("SHA256SUMS")).toList()) {
                Files.copy(f, dir.resolve(f.getFileName()), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        List<String> fromV12 = new ArrayList<>(LAYER_FILES);
        fromV12.add("aoi_list.parquet");
        fromV12.add("aoi_boundaries.geojson");
        for (String f : fromV12) {
            Files.copy(V12_LAYERS.resolve(f), dir.resolve(f), StandardCopyOption.REPLACE_EXISTING);
        }
        return dir;
    }

    /** SHA256SUMS over every regular file of the folder. */
    static void resum(Path dir) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : files.filter(Files::isRegularFile).filter(f -> !f.getFileName().toString().equals("SHA256SUMS")).sorted().toList()) {
                sb.append(Fixtures.sha256(f)).append("  ").append(f.getFileName()).append('\n');
            }
        }
        Files.writeString(dir.resolve("SHA256SUMS"), sb.toString());
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

    static synchronized List<Ev> events() {
        if (events == null) {
            List<Ev> out = new ArrayList<>();
            for (List<Object> r : Duck.query("SELECT event_id, region, CAST(start_date AS VARCHAR), CAST(end_date AS VARCHAR), "
                    + "CAST(n_det AS BIGINT), sum_weight, burned_area_km2, country, adm1_code, sido_nm, sgg_all, centroid_lat, centroid_lon "
                    + "FROM read_parquet(?) ORDER BY event_id", EVENTS)) {
                out.add(new Ev((String) r.get(0), (String) r.get(1), (String) r.get(2), (String) r.get(3), ((Number) r.get(4)).longValue(),
                        (Float) r.get(5), (Double) r.get(6), (String) r.get(7), (String) r.get(8), (String) r.get(9), (String) r.get(10),
                        ((Number) r.get(11)).doubleValue(), ((Number) r.get(12)).doubleValue()));
            }
            events = List.copyOf(out);
        }
        return events;
    }

    /** Period overlap of the contract: start_date ≤ end and end_date ≥ start (ISO dates compare as text). */
    static boolean overlaps(Ev e, String start, String end) {
        return e.start().compareTo(end) <= 0 && e.end().compareTo(start) >= 0;
    }

    /** The contract sorts, each descending with ties by event_id ascending (null burned area last). */
    static Comparator<Ev> order(String sort) {
        Comparator<Ev> byId = Comparator.comparing(Ev::id);
        Map<String, Comparator<Ev>> sorts = new LinkedHashMap<>();
        sorts.put("sum_weight", Comparator.comparing(Ev::sumWeight, Comparator.<Float>reverseOrder()).thenComparing(byId));
        sorts.put("start_date", Comparator.comparing(Ev::start, Comparator.<String>reverseOrder()).thenComparing(byId));
        sorts.put("burned_area_km2", Comparator.comparing(Ev::burned, Comparator.nullsLast(Comparator.<Double>reverseOrder())).thenComparing(byId));
        return sorts.get(sort);
    }

    /** aoi_list SGG rows with their parent SIDO's name (the names sgg_all is resolved against). */
    static List<Sgg> sggs() {
        Map<String, String> sido = new LinkedHashMap<>();
        for (List<Object> r : Duck.query("SELECT aoi_id, name FROM read_parquet(?) WHERE starts_with(aoi_id, 'SIDO_')", AOI_LIST)) {
            sido.put((String) r.get(0), (String) r.get(1));
        }
        List<Sgg> out = new ArrayList<>();
        for (List<Object> r : Duck.query("SELECT aoi_id, name, parent_aoi FROM read_parquet(?) WHERE starts_with(aoi_id, 'SGG_') ORDER BY aoi_id",
                AOI_LIST)) {
            out.add(new Sgg((String) r.get(0), (String) r.get(1), sido.get((String) r.get(2))));
        }
        return out;
    }

    static long count(Path file) {
        return ((Number) Duck.query("SELECT count(*) FROM read_parquet(?)", file).get(0).get(0)).longValue();
    }
}

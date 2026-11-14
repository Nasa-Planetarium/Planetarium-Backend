package io.emberline.hotspots.adapter.out.parquet;

import io.emberline.hotspots.application.port.out.HotspotQueryPort;
import io.emberline.hotspots.application.service.HotspotSettings;
import io.emberline.hotspots.domain.Hotspot;
import io.emberline.hotspots.domain.HotspotScan;
import io.emberline.hotspots.domain.Layer;
import io.emberline.hotspots.domain.PagePosition;
import io.emberline.hotspots.domain.PointFilter;
import io.emberline.hotspots.domain.SortKey;
import io.emberline.hotspots.domain.Totals;
import io.emberline.shared.config.EmberlineProperties;
import io.emberline.shared.error.DataUnavailableException;
import io.emberline.shared.error.ErrorCode;
import io.emberline.shared.sql.SqlResource;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.duckdb.DuckDBConnection;
import org.springframework.stereotype.Component;

/**
 * DuckDB (in-process, in-memory, read-only use) over the active release's harmonized_hotspots parquet files. Only the
 * year/month folders a request touches are listed and read; date_local and bbox predicates are in the SQL. The
 * database may only touch EMBERLINE_RELEASE_ROOT (allowed_directories + enable_external_access=false), extension
 * auto-install/load is off and the configuration is locked. One database per process, one duplicate connection per
 * call.
 */
@Component
class HotspotParquetReader implements HotspotQueryPort {

    static final String FOLDER = "harmonized_hotspots";
    private static final SortKey FIRST = new SortKey(null, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, "");

    private final Optional<Path> root;
    private final HotspotSettings settings;
    private DuckDBConnection database;

    HotspotParquetReader(EmberlineProperties properties, HotspotSettings settings) {
        this.root = properties.release().rootPath();
        this.settings = settings;
    }

    @Override
    public Optional<List<Path>> files(String releaseKey, Layer layer, YearMonth from, YearMonth to) {
        Optional<Path> base = folder(releaseKey);
        if (base.isEmpty()) {
            return Optional.empty();
        }
        List<Path> out = new ArrayList<>();
        for (String sub : layer.folders()) {
            Path layerDir = base.get().resolve(sub);
            if (!Files.isDirectory(layerDir)) {
                return Optional.empty();
            }
            for (YearMonth m = from; !m.isAfter(to); m = m.plusMonths(1)) {
                Path monthDir = layerDir.resolve("year=" + m.getYear()).resolve(String.format("month=%02d", m.getMonthValue()));
                if (Files.isDirectory(monthDir)) {
                    out.addAll(parquetFiles(monthDir));
                }
            }
        }
        return Optional.of(out);
    }

    @Override
    public Totals totals(HotspotScan scan) {
        if (scan.files().isEmpty()) {
            return new Totals(0, 0, 0.0, 0.0, 0);
        }
        return scan.filter().map(f -> filteredTotals(scan, f)).orElseGet(() -> sqlTotals(scan));
    }

    @Override
    public List<Hotspot> page(HotspotScan scan, Optional<PagePosition> after, int n) {
        if (scan.files().isEmpty() || n <= 0) {
            return List.of();
        }
        SortKey from = after.map(PagePosition::key).orElse(FIRST);
        int skip = after.map(PagePosition::skip).orElse(0);
        long limit = scan.filter().isPresent() ? Long.MAX_VALUE : (long) n + skip;
        PointFilter filter = scan.filter().orElse((lat, lon) -> true);
        List<Hotspot> out = new ArrayList<>(Math.min(n, 1024));
        try (Connection c = connection(); PreparedStatement ps = c.prepareStatement(sql("duck_hotspots_page.sql", scan))) {
            int i = bindScan(ps, scan);
            ps.setLong(i++, from.acqDatetimeUtc() == null ? Long.MIN_VALUE : micros(from.acqDatetimeUtc()));
            ps.setDouble(i++, from.latitude());
            ps.setDouble(i++, from.longitude());
            ps.setString(i++, from.satellite());
            ps.setLong(i, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (out.size() < n && rs.next()) {
                    Hotspot h = row(rs);
                    if (!filter.accepts(h.latitude(), h.longitude())) {
                        continue;
                    }
                    if (skip > 0 && h.key().equals(from)) {
                        skip--;
                        continue;
                    }
                    out.add(h);
                }
            }
            return out;
        } catch (SQLException e) {
            throw unreadable(e);
        }
    }

    private Totals sqlTotals(HotspotScan scan) {
        try (Connection c = connection(); PreparedStatement ps = c.prepareStatement(sql("duck_hotspots_totals.sql", scan))) {
            bindScan(ps, scan);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return new Totals(rs.getLong("total"), rs.getDouble("value"), nullableDouble(rs, "value_lo"),
                        nullableDouble(rs, "value_hi"), rs.getLong("n_points"));
            }
        } catch (SQLException e) {
            throw unreadable(e);
        }
    }

    private Totals filteredTotals(HotspotScan scan, PointFilter filter) {
        long total = 0;
        long nPoints = 0;
        double value = 0;
        double lo = 0;
        double hi = 0;
        boolean loNull = false;
        boolean hiNull = false;
        try (Connection c = connection(); PreparedStatement ps = c.prepareStatement(sql("duck_hotspots_weights.sql", scan))) {
            bindScan(ps, scan);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    if (!filter.accepts(rs.getDouble(1), rs.getDouble(2))) {
                        continue;
                    }
                    total++;
                    if (rs.getBoolean(3)) {
                        nPoints++;
                        value += rs.getDouble(4);
                        lo += rs.getDouble(5);
                        loNull |= rs.wasNull();
                        hi += rs.getDouble(6);
                        hiNull |= rs.wasNull();
                    }
                }
            }
        } catch (SQLException e) {
            throw unreadable(e);
        }
        return new Totals(total, value, loNull ? null : lo, hiNull ? null : hi, nPoints);
    }

    private static int bindScan(PreparedStatement ps, HotspotScan scan) throws SQLException {
        ps.setString(1, scan.start().toString());
        ps.setString(2, scan.end().toString());
        ps.setDouble(3, scan.bbox().south());
        ps.setDouble(4, scan.bbox().north());
        ps.setDouble(5, scan.bbox().west());
        ps.setDouble(6, scan.bbox().east());
        return 7;
    }

    private static String sql(String file, HotspotScan scan) {
        String files = scan.files().stream().map(p -> "'" + p.toString().replace("'", "''") + "'").collect(Collectors.joining(", "));
        return SqlResource.text("hotspots/" + file).replace("{files}", files);
    }

    private static Hotspot row(ResultSet rs) throws SQLException {
        long us = rs.getLong("acq_us");
        return new Hotspot(rs.getDouble("latitude"), rs.getDouble("longitude"),
                Instant.EPOCH.plus(us, ChronoUnit.MICROS), LocalDate.parse(rs.getString("date_local")),
                rs.getString("satellite"), rs.getString("instrument"), nullableFloat(rs, "frp"), rs.getString("confidence"),
                rs.getString("daynight"), rs.getLong("cell_id"), rs.getString("adm0_a3"), rs.getString("adm1_code"),
                rs.getFloat("harmonized_weight"), nullableFloat(rs, "weight_lo"), nullableFloat(rs, "weight_hi"),
                rs.getString("weight_source"), rs.getBoolean("is_reference"), rs.getString("source"),
                rs.getString("method_version"));
    }

    private static Float nullableFloat(ResultSet rs, String column) throws SQLException {
        float v = rs.getFloat(column);
        return rs.wasNull() ? null : v;
    }

    private static Double nullableDouble(ResultSet rs, String column) throws SQLException {
        double v = rs.getDouble(column);
        return rs.wasNull() ? null : v;
    }

    private static long micros(Instant t) {
        return ChronoUnit.MICROS.between(Instant.EPOCH, t);
    }

    /** {@code <root>/<release_key>/harmonized_hotspots}, only if it stays inside the root after resolving links. */
    private Optional<Path> folder(String releaseKey) {
        if (root.isEmpty()) {
            return Optional.empty();
        }
        Path dir = root.get().resolve(releaseKey).resolve(FOLDER).normalize();
        if (!dir.startsWith(root.get()) || !Files.isDirectory(dir)) {
            return Optional.empty();
        }
        try {
            Path real = dir.toRealPath();
            return real.startsWith(root.get().toRealPath()) ? Optional.of(real) : Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static List<Path> parquetFiles(Path monthDir) {
        try (Stream<Path> files = Files.list(monthDir)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".parquet") && Files.isRegularFile(p)).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private synchronized Connection connection() throws SQLException {
        if (database == null) {
            database = open();
        }
        return database.duplicate();
    }

    private DuckDBConnection open() throws SQLException {
        Path allowed = root.orElseThrow(() -> new DataUnavailableException(ErrorCode.DATA_NOT_LOADED,
                "release root is not configured"));
        Properties props = new Properties();
        props.setProperty("jdbc_stream_results", "true");
        DuckDBConnection c = (DuckDBConnection) DriverManager.getConnection("jdbc:duckdb:", props);
        try (Statement s = c.createStatement()) {
            Path spill = Path.of(System.getProperty("java.io.tmpdir"), "emberline-hotspots-" + ProcessHandle.current().pid());
            Files.createDirectories(spill);
            String allowedList = Stream.of(realOrSelf(allowed), spill)
                    .map(p -> "'" + (p + "/").replace("'", "''") + "'").collect(Collectors.joining(", "));
            s.execute("SET autoinstall_known_extensions = false");
            s.execute("SET autoload_known_extensions = false");
            s.execute("SET memory_limit = '" + settings.duckdbMemoryLimit().replace("'", "") + "'");
            s.execute("SET threads = " + settings.duckdbThreads());
            s.execute("SET temp_directory = '" + spill.toString().replace("'", "''") + "'");
            s.execute("SET allowed_directories = [" + allowedList + "]");
            s.execute("SET enable_external_access = false");
            s.execute("SET lock_configuration = true");
        } catch (IOException | SQLException e) {
            c.close();
            throw e instanceof SQLException se ? se : new SQLException("cannot prepare the DuckDB spill directory", e);
        }
        return c;
    }

    private static Path realOrSelf(Path p) {
        try {
            return p.toRealPath();
        } catch (IOException e) {
            return p;
        }
    }

    private static DataUnavailableException unreadable(SQLException e) {
        return new DataUnavailableException(ErrorCode.DATA_NOT_LOADED, null,
                "점 자료(harmonized_hotspots)를 읽을 수 없습니다; 잠시 뒤 다시 시도하세요", e);
    }

    @PreDestroy
    synchronized void close() throws SQLException {
        if (database != null) {
            database.close();
            database = null;
        }
    }
}

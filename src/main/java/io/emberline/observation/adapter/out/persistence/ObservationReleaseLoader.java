package io.emberline.observation.adapter.out.persistence;

import io.emberline.observation.domain.CellRow;
import io.emberline.shared.release.load.CodeValues;
import io.emberline.shared.release.load.ColumnKind;
import io.emberline.shared.release.load.ColumnSpec;
import io.emberline.shared.release.load.CopyInputStream;
import io.emberline.shared.release.load.DuckDb;
import io.emberline.shared.release.load.FileChecks;
import io.emberline.shared.release.load.LoadFailures;
import io.emberline.shared.release.load.LoadResult;
import io.emberline.shared.release.load.ParquetColumns;
import io.emberline.shared.release.load.Partitions;
import io.emberline.shared.release.load.ReleaseLoadException;
import io.emberline.shared.release.load.ReleaseLoader;
import io.emberline.shared.release.load.ReleaseSource;
import io.emberline.shared.release.load.ValidationReport;
import io.emberline.shared.sql.SqlResource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.sql.DataSource;
import org.duckdb.DuckDBConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * {@code observation.cell_month} from harmonized_cell_month.parquet (51.5M rows in v1-m2v-2026-11-14): streamed with
 * DuckDB read_parquet → PostgreSQL COPY in parallel month ranges (one DuckDB instance, one PG connection per worker),
 * indexes after the COPY. method_version is checked equal to m2v_meta.json and not repeated per row. The v1.1 columns
 * value_projected and as_of_date are optional and kept for status = partial rows only (a complete month's
 * value_projected equals value); a cell file without as_of_date takes the partial month's as_of_date from
 * aoi_month.parquet.
 * <p>
 * {@code observation.cell_day_frp} from harmonized_cell_day_frp.parquet (v1.2, optional: 97.8M rows in
 * data-v1.2-2026-11-15): the 6 columns as stored, streamed the same way in parallel cell_id ranges cut at row-group
 * boundaries (the file is cell_id-ordered), unique index (date_local, cell_id) after the COPY. Only a release that ships
 * the file gets the partition; the stage report gets its rows, load milliseconds and partition bytes.
 */
@Component
class ObservationReleaseLoader implements ReleaseLoader {

    static final String CELLS = "harmonized_cell_month.parquet";
    static final String T_CELLS = "observation.cell_month";
    static final String AOI_MONTH = "aoi_month.parquet";
    static final String FRP = "harmonized_cell_day_frp.parquet";
    static final String T_FRP = "observation.cell_day_frp";
    /** Load report keys next to the row count of {@link #T_FRP}. */
    static final String FRP_LOAD_MS = T_FRP + "#load_ms";
    static final String FRP_BYTES = T_FRP + "#bytes";
    static final String FRP_INDEX_BYTES = T_FRP + "#index_bytes";
    private static final Logger log = LoggerFactory.getLogger(ObservationReleaseLoader.class);

    static final List<ColumnSpec> COLUMNS = List.of(
            ColumnSpec.required("cell_id", ColumnKind.INTEGER),
            ColumnSpec.required("cell_lat", ColumnKind.NUMBER, "lat"),
            ColumnSpec.required("cell_lon", ColumnKind.NUMBER, "lon"),
            ColumnSpec.required("year", ColumnKind.INTEGER),
            ColumnSpec.required("month", ColumnKind.INTEGER),
            ColumnSpec.required("value", ColumnKind.NUMBER, "n_det", "count_harmonized"),
            ColumnSpec.required("value_lo", ColumnKind.NUMBER, "n_det_q10"),
            ColumnSpec.required("value_hi", ColumnKind.NUMBER, "n_det_q90"),
            ColumnSpec.required("raw_modis", ColumnKind.INTEGER, "n_det_modis_raw", "count_raw_modis", "count_modis"),
            ColumnSpec.required("raw_viirs", ColumnKind.INTEGER, "n_det_viirs_raw", "count_raw_viirs", "count_viirs"),
            ColumnSpec.required("value_source", ColumnKind.TEXT),
            ColumnSpec.required("data_source", ColumnKind.TEXT, "source_flag"),
            ColumnSpec.required("status", ColumnKind.TEXT),
            ColumnSpec.required("method_version", ColumnKind.TEXT, "model_version"),
            ColumnSpec.optional("value_projected", ColumnKind.NUMBER),
            ColumnSpec.optional("as_of_date", ColumnKind.DATE));
    /** aoi_month.parquet columns read for the as_of_date of partial months (validated by the harmonized loader). */
    static final List<ColumnSpec> AS_OF_COLUMNS = List.of(
            ColumnSpec.required("year", ColumnKind.INTEGER),
            ColumnSpec.required("month", ColumnKind.INTEGER),
            ColumnSpec.required("status", ColumnKind.TEXT),
            ColumnSpec.optional("as_of_date", ColumnKind.DATE));
    /** harmonized_cell_day_frp.parquet: all 6 columns (MAPPING §8.2), none optional. */
    static final List<ColumnSpec> FRP_COLUMNS = List.of(
            ColumnSpec.required("cell_id", ColumnKind.INTEGER),
            ColumnSpec.required("date_local", ColumnKind.DATE),
            ColumnSpec.required("frp_sum", ColumnKind.NUMBER),
            ColumnSpec.required("frp_snpp_equiv_sum", ColumnKind.NUMBER),
            ColumnSpec.required("frp_snpp_equiv_max", ColumnKind.NUMBER),
            ColumnSpec.required("n_points", ColumnKind.INTEGER));

    private final DuckDb duckDb;
    private final Partitions partitions;
    private final DataSource dataSource;

    ObservationReleaseLoader(DuckDb duckDb, Partitions partitions, DataSource dataSource) {
        this.duckDb = duckDb;
        this.partitions = partitions;
        this.dataSource = dataSource;
    }

    @Override
    public String context() {
        return "observation";
    }

    @Override
    public int order() {
        return 30;
    }

    @Override
    public List<ReleaseFile> files() {
        return List.of(new ReleaseFile(CELLS, true), new ReleaseFile(FRP, false));
    }

    @Override
    public void validate(ReleaseSource source) {
        ValidationReport report = source.report();
        String file = source.file(CELLS).toString();
        try (Connection duck = duckDb.open()) {
            ParquetColumns c = ParquetColumns.inspect(duck, file, CELLS, COLUMNS, report);
            if (!c.ok()) {
                return;
            }
            FileChecks.codeValues(duck, file, CELLS, c, "value_source", CodeValues.VALUE_SOURCES, CodeValues.VALUE_SOURCE_V0, report);
            FileChecks.codeValues(duck, file, CELLS, c, "data_source", CodeValues.DATA_SOURCES, Map.of(), report);
            FileChecks.codeValues(duck, file, CELLS, c, "status", CodeValues.STATUSES, Map.of(), report);
            String mv = source.methodVersion() == null ? "" : source.methodVersion();
            FileChecks.codeValues(duck, file, CELLS, c, "method_version", Set.of(mv), Map.of(), report, "METHOD_VERSION_MISMATCH");
            if (FileChecks.count(duck, file, c.render(SqlResource.text("observation/duck_cell_month_checks.sql"))) > 0) {
                report.reject("KEY_INVALID", CELLS, null);
            }
            FileChecks.dataCases(duck, file, CELLS, c.render(SqlResource.text("observation/duck_cell_month_cases.sql")), report);
            // duplicate (year, month, cell_id) keys are caught by the unique index built after COPY (no 51M-row DISTINCT here)
        } catch (SQLException e) {
            report.reject("FILE_UNREADABLE", CELLS, null);
        }
        if (source.has(FRP)) {
            validateFrp(source, report);
        }
    }

    /** Columns and kinds, impossible keys; duplicate (date_local, cell_id) keys are caught by the unique index. */
    private void validateFrp(ReleaseSource source, ValidationReport report) {
        String file = source.file(FRP).toString();
        try (Connection duck = duckDb.open()) {
            ParquetColumns c = ParquetColumns.inspect(duck, file, FRP, FRP_COLUMNS, report);
            if (c.ok() && FileChecks.count(duck, file, c.render(SqlResource.text("observation/duck_cell_day_frp_checks.sql"))) > 0) {
                report.reject("KEY_INVALID", FRP, null);
            }
        } catch (SQLException e) {
            report.reject("FILE_UNREADABLE", FRP, null);
        }
    }

    @Override
    public LoadResult load(ReleaseSource source) {
        long id = source.releaseId();
        String file = source.file(CELLS).toString();
        long started = System.nanoTime();
        try (Connection duck = duckDb.open()) {
            ParquetColumns c = ParquetColumns.inspect(duck, file, CELLS, COLUMNS, new ValidationReport());
            List<int[]> ranges = ranges(duck, file, c, duckDb.copyWorkers());
            Map<Integer, String> asOf = c.has("as_of_date") ? Map.of() : partialAsOf(duck, source);
            partitions.create(T_CELLS, id);
            String select = c.render(SqlResource.text("observation/duck_cell_month.sql"));
            String copy = SqlResource.text("observation/copy_cell_month.sql");
            long total = 0;
            ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, ranges.size()));
            try {
                List<Future<Long>> parts = new ArrayList<>();
                for (int[] range : ranges) {
                    Connection worker = ((DuckDBConnection) duck).duplicate();
                    parts.add(pool.submit(() -> copyRange(worker, select, copy, file, id, range, asOf)));
                }
                for (Future<Long> part : parts) {
                    total += part.get();
                }
            } catch (java.util.concurrent.ExecutionException e) {
                throw e.getCause() instanceof SQLException sql ? sql : new SQLException("cell COPY failed", e.getCause());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SQLException("cell COPY interrupted", e);
            } finally {
                pool.shutdownNow();
            }
            double secs = (System.nanoTime() - started) / 1e9;
            log.info("COPY {} total rows={} workers={} seconds={} rows_per_s={}", Partitions.child(T_CELLS, id), total,
                    ranges.size(), String.format("%.1f", secs), Math.round(total / Math.max(secs, 1e-6)));
            partitions.finish(T_CELLS, id, SqlResource.statements("observation/index_cell_month.sql"));
            Map<String, Long> rows = new LinkedHashMap<>();
            rows.put(T_CELLS, total);
            if (source.has(FRP)) {
                rows.putAll(loadFrp(duck, source));
            }
            return new LoadResult(rows);
        } catch (SQLException e) {
            throw new ReleaseLoadException(LoadFailures.reason(e, "observation"), e);
        }
    }

    /**
     * harmonized_cell_day_frp.parquet → {@code observation.cell_day_frp}: parallel COPY over cell_id ranges, then the
     * unique (date_local, cell_id) index, CHECK, ANALYZE, ATTACH. Returns rows, load milliseconds and partition bytes.
     */
    private Map<String, Long> loadFrp(Connection duck, ReleaseSource source) throws SQLException {
        long id = source.releaseId();
        String file = source.file(FRP).toString();
        long started = System.nanoTime();
        ParquetColumns c = ParquetColumns.inspect(duck, file, FRP, FRP_COLUMNS, new ValidationReport());
        List<long[]> ranges = frpRanges(duck, file, duckDb.copyWorkers());
        partitions.create(T_FRP, id);
        String select = c.render(SqlResource.text("observation/duck_cell_day_frp.sql"));
        String copy = SqlResource.text("observation/copy_cell_day_frp.sql");
        long total = 0;
        ExecutorService pool = Executors.newFixedThreadPool(ranges.size());
        try {
            List<Future<Long>> parts = new ArrayList<>();
            for (long[] range : ranges) {
                Connection worker = ((DuckDBConnection) duck).duplicate();
                parts.add(pool.submit(() -> copyFrpRange(worker, select, copy, file, id, range)));
            }
            for (Future<Long> part : parts) {
                total += part.get();
            }
        } catch (java.util.concurrent.ExecutionException e) {
            throw e.getCause() instanceof SQLException sql ? sql : new SQLException("FRP COPY failed", e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SQLException("FRP COPY interrupted", e);
        } finally {
            pool.shutdownNow();
        }
        double copySecs = (System.nanoTime() - started) / 1e9;
        log.info("COPY {} total rows={} workers={} seconds={} rows_per_s={}", Partitions.child(T_FRP, id), total,
                ranges.size(), String.format("%.1f", copySecs), Math.round(total / Math.max(copySecs, 1e-6)));
        partitions.finish(T_FRP, id, SqlResource.statements("observation/index_cell_day_frp.sql"));
        long ms = Math.round((System.nanoTime() - started) / 1e6);
        long[] bytes = frpBytes(Partitions.child(T_FRP, id));
        log.info("LOAD {} rows={} seconds={} table_bytes={} index_bytes={} total_bytes={}", Partitions.child(T_FRP, id), total,
                String.format("%.1f", ms / 1e3), bytes[0], bytes[1], bytes[2]);
        Map<String, Long> out = new LinkedHashMap<>();
        out.put(T_FRP, total);
        out.put(FRP_LOAD_MS, ms);
        out.put(FRP_BYTES, bytes[2]);
        out.put(FRP_INDEX_BYTES, bytes[1]);
        return out;
    }

    private long copyFrpRange(Connection worker, String select, String copy, String file, long id, long[] range)
            throws SQLException {
        try (worker; PreparedStatement ps = worker.prepareStatement(select)) {
            ps.setString(1, file);
            ps.setLong(2, range[0]);
            ps.setLong(3, range[1]);
            try (ResultSet r = ps.executeQuery()) {
                return partitions.copy(T_FRP, id, copy, new CopyInputStream(r, (row, line) -> line
                        .integer(row.getLong(1), false)
                        .num(row.getDouble(2), row.wasNull()).num(row.getDouble(3), row.wasNull())
                        .text(row.getString(4))
                        .real(row.getFloat(5), row.wasNull())
                        .integer(row.getInt(6), false)));
            }
        }
    }

    /**
     * Disjoint cell_id ranges {@code (lo, hi]} covering every id, cut at row-group boundaries so each holds about the
     * same number of rows. The file is cell_id-ordered (MAPPING §8.4); if it is not, the ranges stay disjoint and
     * complete, only less balanced. Without usable statistics: one range.
     */
    static List<long[]> frpRanges(Connection duck, String file, int workers) throws SQLException {
        List<long[]> groups = new ArrayList<>();
        long total = 0;
        try (PreparedStatement ps = duck.prepareStatement(SqlResource.text("observation/duck_cell_day_frp_groups.sql"))) {
            ps.setString(1, file);
            ps.setString(2, "cell_id");
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String max = rs.getString(4);
                    if (max == null || !max.matches("^-?[0-9]{1,18}$")) {
                        return List.<long[]>of(new long[] {Long.MIN_VALUE, Long.MAX_VALUE});
                    }
                    groups.add(new long[] {rs.getLong(2), Long.parseLong(max)});
                    total += rs.getLong(2);
                }
            }
        }
        groups.sort((a, b) -> Long.compare(a[1], b[1]));
        List<long[]> ranges = new ArrayList<>();
        long target = Math.max(1, (total + workers - 1) / workers);
        long acc = 0;
        long lo = Long.MIN_VALUE;
        for (int i = 0; i < groups.size() - 1; i++) {
            acc += groups.get(i)[0];
            long cut = groups.get(i)[1];
            if (acc >= target && cut > lo && cut < groups.get(groups.size() - 1)[1]) {
                ranges.add(new long[] {lo, cut});
                lo = cut;
                acc = 0;
            }
        }
        ranges.add(new long[] {lo, Long.MAX_VALUE});
        return ranges;
    }

    /** pg_table_size, pg_indexes_size, pg_total_relation_size of a partition. */
    private long[] frpBytes(String child) throws SQLException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(SqlResource.text("observation/size_cell_day_frp.sql"))) {
            ps.setString(1, child);
            ps.setString(2, child);
            ps.setString(3, child);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return new long[] {rs.getLong(1), rs.getLong(2), rs.getLong(3)};
            }
        }
    }

    private long copyRange(Connection worker, String select, String copy, String file, long id, int[] range,
                           Map<Integer, String> partialAsOf) throws SQLException {
        try (worker; PreparedStatement ps = worker.prepareStatement(select)) {
            ps.setString(1, file);
            ps.setInt(2, range[0]);
            ps.setInt(3, range[1]);
            ps.setInt(4, range[0] / 100);
            ps.setInt(5, range[1] / 100);
            try (ResultSet r = ps.executeQuery()) {
                return partitions.copy(T_CELLS, id, copy, new CopyInputStream(r, (row, line) -> line
                        .integer(row.getLong(1), false)
                        .num(row.getDouble(2), row.wasNull()).num(row.getDouble(3), row.wasNull()).num(row.getDouble(4), row.wasNull())
                        .real(row.getFloat(5), false).real(row.getFloat(6), false)
                        .integer(row.getInt(7), row.wasNull()).integer(row.getInt(8), row.wasNull())
                        .integer(row.getInt(9), false).integer(row.getInt(10), false)
                        .text(CodeValues.valueSource(row.getString(11))).text(row.getString(12)).text(row.getString(13))
                        .num(row.getDouble(14), row.wasNull() || !CellRow.PARTIAL.equals(row.getString(13)))
                        .text(CellRow.PARTIAL.equals(row.getString(13)) ? asOf(row.getString(15), partialAsOf, row.getInt(9), row.getInt(10)) : null)));
            }
        }
    }

    private static String asOf(String fromFile, Map<Integer, String> partialAsOf, int year, int month) {
        return fromFile != null ? fromFile : partialAsOf.get(year * 100 + month);
    }

    /** YYYYMM → as_of_date of the partial months of aoi_month.parquet; empty when that file has no as_of_date. */
    static Map<Integer, String> partialAsOf(Connection duck, ReleaseSource source) throws SQLException {
        String file = source.file(AOI_MONTH).toString();
        ParquetColumns m = ParquetColumns.inspect(duck, file, AOI_MONTH, AS_OF_COLUMNS, new ValidationReport());
        if (!m.ok() || !m.has("as_of_date")) {
            return Map.of();
        }
        Map<Integer, String> out = new HashMap<>();
        try (PreparedStatement ps = duck.prepareStatement(m.render(SqlResource.text("observation/duck_partial_as_of.sql")))) {
            ps.setString(1, file);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.put(rs.getInt(1), rs.getString(2));
                }
            }
        }
        return Map.copyOf(out);
    }

    /** Contiguous YYYYMM ranges with about the same number of rows each. */
    static List<int[]> ranges(Connection duck, String file, ParquetColumns c, int workers) throws SQLException {
        List<int[]> months = new ArrayList<>();
        long total = 0;
        try (PreparedStatement ps = duck.prepareStatement(c.render(SqlResource.text("observation/duck_cell_month_counts.sql")))) {
            ps.setString(1, file);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    months.add(new int[] {rs.getInt(1), (int) rs.getLong(2)});
                    total += rs.getLong(2);
                }
            }
        }
        List<int[]> ranges = new ArrayList<>();
        if (months.isEmpty()) {
            return ranges;
        }
        long target = Math.max(1, (total + workers - 1) / workers);
        long acc = 0;
        int start = months.get(0)[0];
        for (int i = 0; i < months.size(); i++) {
            acc += months.get(i)[1];
            boolean last = i == months.size() - 1;
            if (acc >= target || last) {
                ranges.add(new int[] {start, months.get(i)[0]});
                acc = 0;
                if (!last) {
                    start = months.get(i + 1)[0];
                }
            }
        }
        return ranges;
    }

    @Override
    public void drop(long releaseId) {
        try {
            partitions.drop(T_CELLS, releaseId);
            partitions.drop(T_FRP, releaseId);
        } catch (SQLException e) {
            throw new IllegalStateException("cannot drop observation partitions", e);
        }
    }
}

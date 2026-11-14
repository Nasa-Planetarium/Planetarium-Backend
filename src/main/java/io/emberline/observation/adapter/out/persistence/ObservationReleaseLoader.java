package io.emberline.observation.adapter.out.persistence;

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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.duckdb.DuckDBConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * {@code observation.cell_month} from harmonized_cell_month.parquet (51.5M rows in v1-m2v-2026-11-14): streamed with
 * DuckDB read_parquet → PostgreSQL COPY in parallel month ranges (one DuckDB instance, one PG connection per worker),
 * indexes after the COPY. method_version is checked equal to m2v_meta.json and not repeated per row.
 */
@Component
class ObservationReleaseLoader implements ReleaseLoader {

    static final String CELLS = "harmonized_cell_month.parquet";
    static final String T_CELLS = "observation.cell_month";
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
            ColumnSpec.required("method_version", ColumnKind.TEXT, "model_version"));

    private final DuckDb duckDb;
    private final Partitions partitions;

    ObservationReleaseLoader(DuckDb duckDb, Partitions partitions) {
        this.duckDb = duckDb;
        this.partitions = partitions;
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
        return List.of(new ReleaseFile(CELLS, true));
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
    }

    @Override
    public LoadResult load(ReleaseSource source) {
        long id = source.releaseId();
        String file = source.file(CELLS).toString();
        long started = System.nanoTime();
        try (Connection duck = duckDb.open()) {
            ParquetColumns c = ParquetColumns.inspect(duck, file, CELLS, COLUMNS, new ValidationReport());
            List<int[]> ranges = ranges(duck, file, c, duckDb.copyWorkers());
            partitions.create(T_CELLS, id);
            String select = c.render(SqlResource.text("observation/duck_cell_month.sql"));
            String copy = SqlResource.text("observation/copy_cell_month.sql");
            long total = 0;
            ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, ranges.size()));
            try {
                List<Future<Long>> parts = new ArrayList<>();
                for (int[] range : ranges) {
                    Connection worker = ((DuckDBConnection) duck).duplicate();
                    parts.add(pool.submit(() -> copyRange(worker, select, copy, file, id, range)));
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
            return new LoadResult(Map.of(T_CELLS, total));
        } catch (SQLException e) {
            throw new ReleaseLoadException(LoadFailures.reason(e, "observation"), e);
        }
    }

    private long copyRange(Connection worker, String select, String copy, String file, long id, int[] range) throws SQLException {
        try (worker; PreparedStatement ps = worker.prepareStatement(select)) {
            ps.setString(1, file);
            ps.setInt(2, range[0]);
            ps.setInt(3, range[1]);
            try (ResultSet r = ps.executeQuery()) {
                return partitions.copy(T_CELLS, id, copy, new CopyInputStream(r, (row, line) -> line
                        .integer(row.getLong(1), false)
                        .num(row.getDouble(2), row.wasNull()).num(row.getDouble(3), row.wasNull()).num(row.getDouble(4), row.wasNull())
                        .real(row.getFloat(5), false).real(row.getFloat(6), false)
                        .integer(row.getInt(7), row.wasNull()).integer(row.getInt(8), row.wasNull())
                        .integer(row.getInt(9), false).integer(row.getInt(10), false)
                        .text(CodeValues.valueSource(row.getString(11))).text(row.getString(12)).text(row.getString(13))));
            }
        }
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
        } catch (SQLException e) {
            throw new IllegalStateException("cannot drop observation partitions", e);
        }
    }
}

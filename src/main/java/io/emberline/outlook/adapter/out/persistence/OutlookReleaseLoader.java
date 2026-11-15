package io.emberline.outlook.adapter.out.persistence;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * outlook partitions: {@code grid_occurrence} (grid_occurrence_nextmonth_latest.parquet) and
 * {@code aoi_occurrence} (aoi_occurrence_nextmonth_latest.parquet) — the columns MAPPING §8.2 serves, copied as the
 * file has them (float32 widened exactly, nothing recomputed; n_years float → integer). Both files are optional: a
 * release without them (v1, v1.1) gets no partition and no {@code rows} key, and the outlook routes answer 503
 * layer_not_loaded. A missing column, a null in a served non-null column, NaN or infinity, more than one head value
 * (target_month, as_of_date, grid model) or a non-integral n_years rejects the release; a cell_id that breaks the
 * contract's cell_id formula is a warning.
 */
@Component
class OutlookReleaseLoader implements ReleaseLoader {

    static final String GRID = "grid_occurrence_nextmonth_latest.parquet";
    static final String AOI = "aoi_occurrence_nextmonth_latest.parquet";
    static final String T_GRID = "outlook.grid_occurrence";
    static final String T_AOI = "outlook.aoi_occurrence";

    static final List<ColumnSpec> GRID_COLUMNS = List.of(
            ColumnSpec.required("cell_id", ColumnKind.INTEGER),
            ColumnSpec.required("lat", ColumnKind.NUMBER),
            ColumnSpec.required("lon", ColumnKind.NUMBER),
            ColumnSpec.required("target_month", ColumnKind.TEXT),
            ColumnSpec.required("p_any", ColumnKind.NUMBER),
            ColumnSpec.required("p_none", ColumnKind.NUMBER),
            ColumnSpec.required("baseline_p", ColumnKind.NUMBER),
            ColumnSpec.required("persistent_source", ColumnKind.BOOLEAN),
            ColumnSpec.required("model", ColumnKind.TEXT),
            ColumnSpec.required("as_of_date", ColumnKind.DATE),
            ColumnSpec.required("adm0", ColumnKind.TEXT),
            ColumnSpec.required("continent", ColumnKind.TEXT),
            ColumnSpec.required("rate_5y", ColumnKind.NUMBER),
            ColumnSpec.required("lag1_projected", ColumnKind.BOOLEAN));
    static final List<ColumnSpec> AOI_COLUMNS = List.of(
            ColumnSpec.required("aoi_id", ColumnKind.TEXT),
            ColumnSpec.required("target_month", ColumnKind.TEXT),
            ColumnSpec.required("p_any", ColumnKind.NUMBER),
            ColumnSpec.required("p_none", ColumnKind.NUMBER),
            ColumnSpec.required("baseline_p", ColumnKind.NUMBER),
            ColumnSpec.required("model", ColumnKind.TEXT),
            ColumnSpec.required("as_of_date", ColumnKind.DATE),
            ColumnSpec.required("suspected_industrial", ColumnKind.BOOLEAN),
            ColumnSpec.required("rate_5y", ColumnKind.NUMBER),
            ColumnSpec.required("n_years", ColumnKind.NUMBER),
            ColumnSpec.required("lag1_projected", ColumnKind.BOOLEAN));

    private final DuckDb duckDb;
    private final Partitions partitions;

    OutlookReleaseLoader(DuckDb duckDb, Partitions partitions) {
        this.duckDb = duckDb;
        this.partitions = partitions;
    }

    @Override
    public String context() {
        return "outlook";
    }

    @Override
    public int order() {
        return 50;
    }

    @Override
    public List<ReleaseFile> files() {
        return List.of(new ReleaseFile(GRID, false), new ReleaseFile(AOI, false));
    }

    @Override
    public void validate(ReleaseSource source) {
        ValidationReport report = source.report();
        if (source.has(GRID)) {
            try (Connection duck = duckDb.open()) {
                String file = source.file(GRID).toString();
                ParquetColumns c = ParquetColumns.inspect(duck, file, GRID, GRID_COLUMNS, report);
                if (c.ok()) {
                    FileChecks.uniqueKey(duck, file, GRID, c, "{cell_id}", report);
                    try (PreparedStatement ps = duck.prepareStatement(c.render(SqlResource.text("outlook/duck_grid_checks.sql")))) {
                        ps.setString(1, file);
                        try (ResultSet rs = ps.executeQuery()) {
                            rs.next();
                            nulls(report, GRID, rs.getLong("null_required"), rs.getLong("not_finite"));
                            head(report, GRID, "target_month", rs.getLong("target_months"));
                            head(report, GRID, "as_of_date", rs.getLong("as_of_dates"));
                            head(report, GRID, "model", rs.getLong("models"));
                            long formula = rs.getLong("cell_id_formula");
                            if (formula > 0) {
                                report.warn("CELL_ID_FORMULA", GRID, formula + " rows kept as stored");
                            }
                        }
                    }
                }
            } catch (SQLException e) {
                report.reject("FILE_UNREADABLE", GRID, null);
            }
        }
        if (source.has(AOI)) {
            try (Connection duck = duckDb.open()) {
                String file = source.file(AOI).toString();
                ParquetColumns c = ParquetColumns.inspect(duck, file, AOI, AOI_COLUMNS, report);
                if (c.ok()) {
                    FileChecks.uniqueKey(duck, file, AOI, c, "{aoi_id}", report);
                    try (PreparedStatement ps = duck.prepareStatement(c.render(SqlResource.text("outlook/duck_aoi_checks.sql")))) {
                        ps.setString(1, file);
                        try (ResultSet rs = ps.executeQuery()) {
                            rs.next();
                            nulls(report, AOI, rs.getLong("null_required"), rs.getLong("not_finite"));
                            head(report, AOI, "target_month", rs.getLong("target_months"));
                            head(report, AOI, "as_of_date", rs.getLong("as_of_dates"));
                            long notIntegral = rs.getLong("n_years_not_integral");
                            if (notIntegral > 0) {
                                report.reject("NOT_INTEGRAL", AOI, "n_years " + notIntegral + " rows");
                            }
                        }
                    }
                }
            } catch (SQLException e) {
                report.reject("FILE_UNREADABLE", AOI, null);
            }
        }
    }

    private static void nulls(ValidationReport report, String file, long nullRows, long notFiniteRows) {
        if (nullRows > 0) {
            report.reject("NULL_VALUE", file, nullRows + " rows with a null in a served non-null column");
        }
        if (notFiniteRows > 0) {
            report.reject("NOT_FINITE", file, notFiniteRows + " rows with NaN or infinity");
        }
    }

    /** The response head carries one value; an empty file has none (served as not loaded), more than one is refused. */
    private static void head(ValidationReport report, String file, String column, long distinct) {
        if (distinct > 1) {
            report.reject("HEAD_NOT_UNIQUE", file, column + " " + distinct + " values");
        }
    }

    @Override
    public LoadResult load(ReleaseSource source) {
        long id = source.releaseId();
        Map<String, Long> rows = new LinkedHashMap<>();
        try (Connection duck = duckDb.open()) {
            ValidationReport scratch = new ValidationReport();
            // only a release that ships the file gets the partition (v1, v1.1: none, routes answer 503 layer_not_loaded)
            if (source.has(GRID)) {
                ParquetColumns g = ParquetColumns.inspect(duck, source.file(GRID).toString(), GRID, GRID_COLUMNS, scratch);
                partitions.create(T_GRID, id);
                try (PreparedStatement ps = duck.prepareStatement(g.render(SqlResource.text("outlook/duck_grid_occurrence.sql")))) {
                    ps.setString(1, source.file(GRID).toString());
                    try (ResultSet rs = ps.executeQuery()) {
                        rows.put(T_GRID, partitions.copy(T_GRID, id, SqlResource.text("outlook/copy_grid_occurrence.sql"),
                                new CopyInputStream(rs, (r, l) -> l.integer(r.getLong(1), false)
                                        .num(r.getDouble(2), false).num(r.getDouble(3), false).text(r.getString(4))
                                        .num(r.getDouble(5), false).num(r.getDouble(6), r.wasNull())
                                        .num(r.getDouble(7), false).bool(r.getBoolean(8), false).text(r.getString(9))
                                        .text(r.getString(10)).text(r.getString(11)).text(r.getString(12))
                                        .num(r.getDouble(13), r.wasNull()).bool(r.getBoolean(14), r.wasNull()))));
                    }
                }
                partitions.finish(T_GRID, id, SqlResource.statements("outlook/index_grid_occurrence.sql"));
            }
            if (source.has(AOI)) {
                ParquetColumns a = ParquetColumns.inspect(duck, source.file(AOI).toString(), AOI, AOI_COLUMNS, scratch);
                partitions.create(T_AOI, id);
                try (PreparedStatement ps = duck.prepareStatement(a.render(SqlResource.text("outlook/duck_aoi_occurrence.sql")))) {
                    ps.setString(1, source.file(AOI).toString());
                    try (ResultSet rs = ps.executeQuery()) {
                        rows.put(T_AOI, partitions.copy(T_AOI, id, SqlResource.text("outlook/copy_aoi_occurrence.sql"),
                                new CopyInputStream(rs, (r, l) -> l.text(r.getString(1)).text(r.getString(2))
                                        .num(r.getDouble(3), false).num(r.getDouble(4), r.wasNull())
                                        .num(r.getDouble(5), r.wasNull()).text(r.getString(6)).text(r.getString(7))
                                        .bool(r.getBoolean(8), false).num(r.getDouble(9), r.wasNull())
                                        .integer(r.getLong(10), r.wasNull()).bool(r.getBoolean(11), r.wasNull()))));
                    }
                }
                partitions.finish(T_AOI, id, SqlResource.statements("outlook/index_aoi_occurrence.sql"));
            }
            return new LoadResult(rows);
        } catch (SQLException e) {
            throw new ReleaseLoadException(LoadFailures.reason(e, "outlook"), e);
        }
    }

    @Override
    public void drop(long releaseId) {
        try {
            partitions.drop(T_GRID, releaseId);
            partitions.drop(T_AOI, releaseId);
        } catch (SQLException e) {
            throw new IllegalStateException("cannot drop outlook partitions", e);
        }
    }
}

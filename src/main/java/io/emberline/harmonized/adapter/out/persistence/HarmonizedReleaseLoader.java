package io.emberline.harmonized.adapter.out.persistence;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * harmonized partitions: {@code harmonized.aoi_month} and {@code harmonized.aoi_month_compare}, values copied
 * as-is (A1), including the v1.1 columns value_projected and as_of_date (optional, NULL for a v1 release) and the v1.2 r3
 * compare columns baseline_period, continuity_ratio, continuity_source (optional, NULL before r3). Missing
 * months keep value NULL (E2); out-of-interval values are kept (E3); v0 value_source names are translated to v1 and
 * reported. {@code harmonized.cell_center} = one centre per cell of harmonized_cell_month.parquet (AOI cell sets).
 * harmonized_cell_day.parquet and harmonized_residual_cell_month.parquet are NOT loaded (ruling R-CD1): when present
 * they must be listed in SHA256SUMS and have the expected columns and code values; the API reads them in place.
 */
@Component
class HarmonizedReleaseLoader implements ReleaseLoader {

    static final String AOI_MONTH = "aoi_month.parquet";
    static final String COMPARE = "aoi_month_compare.parquet";
    static final String T_MONTH = "harmonized.aoi_month";
    static final String T_COMPARE = "harmonized.aoi_month_compare";
    static final String T_CENTER = "harmonized.cell_center";
    static final String CELL_MONTH = "harmonized_cell_month.parquet";
    public static final String CELL_DAY = "harmonized_cell_day.parquet";
    public static final String RESIDUAL = "harmonized_residual_cell_month.parquet";
    static final java.util.Set<String> RESIDUAL_TYPES = java.util.Set.of("modis_unseen", "missing_day_fill");
    /**
     * cell_day also labels the day-1 carrier rows of background predictions (value 0, n_points 0) modis_m2v_background,
     * and (v1.1, ruling R-VS1: the file is canonical) the current NRT day's MODIS-fallback predictions
     * modis_fallback_m2v_predicted.
     */
    static final java.util.Set<String> CELL_DAY_SOURCES = java.util.stream.Stream.concat(CodeValues.VALUE_SOURCES.stream(),
            java.util.stream.Stream.of("modis_m2v_background", "modis_fallback_m2v_predicted"))
            .collect(java.util.stream.Collectors.toUnmodifiableSet());

    static final List<ColumnSpec> MONTH_COLUMNS = List.of(
            ColumnSpec.required("aoi_id", ColumnKind.TEXT),
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
            ColumnSpec.required("n_cells", ColumnKind.INTEGER),
            ColumnSpec.optional("value_projected", ColumnKind.NUMBER),
            ColumnSpec.optional("as_of_date", ColumnKind.DATE));
    static final List<ColumnSpec> COMPARE_COLUMNS = List.of(
            ColumnSpec.required("aoi_id", ColumnKind.TEXT),
            ColumnSpec.required("year", ColumnKind.INTEGER),
            ColumnSpec.required("month", ColumnKind.INTEGER),
            ColumnSpec.required("value", ColumnKind.NUMBER),
            ColumnSpec.required("baseline_median", ColumnKind.NUMBER),
            ColumnSpec.required("baseline_p25", ColumnKind.NUMBER),
            ColumnSpec.required("baseline_p75", ColumnKind.NUMBER),
            ColumnSpec.required("percentile", ColumnKind.NUMBER),
            ColumnSpec.required("ratio_to_median", ColumnKind.NUMBER),
            ColumnSpec.required("n_baseline_years", ColumnKind.INTEGER),
            ColumnSpec.required("verdict", ColumnKind.TEXT),
            ColumnSpec.optional("status", ColumnKind.TEXT),
            ColumnSpec.optional("low_sample", ColumnKind.BOOLEAN),
            ColumnSpec.optional("value_projected", ColumnKind.NUMBER),
            ColumnSpec.optional("as_of_date", ColumnKind.DATE),
            ColumnSpec.optional("baseline_period", ColumnKind.TEXT),
            ColumnSpec.optional("continuity_ratio", ColumnKind.NUMBER),
            ColumnSpec.optional("continuity_source", ColumnKind.TEXT));
    static final List<ColumnSpec> CENTER_COLUMNS = List.of(
            ColumnSpec.required("cell_id", ColumnKind.INTEGER),
            ColumnSpec.required("cell_lat", ColumnKind.NUMBER, "lat"),
            ColumnSpec.required("cell_lon", ColumnKind.NUMBER, "lon"));
    static final List<ColumnSpec> CELL_DAY_COLUMNS = List.of(
            ColumnSpec.required("cell_id", ColumnKind.INTEGER),
            ColumnSpec.required("date_local", ColumnKind.DATE),
            ColumnSpec.required("year", ColumnKind.INTEGER),
            ColumnSpec.required("month", ColumnKind.INTEGER),
            ColumnSpec.required("value", ColumnKind.NUMBER),
            ColumnSpec.required("value_lo", ColumnKind.NUMBER),
            ColumnSpec.required("value_hi", ColumnKind.NUMBER),
            ColumnSpec.required("n_points", ColumnKind.INTEGER),
            ColumnSpec.required("value_source", ColumnKind.TEXT),
            ColumnSpec.required("residual_month", ColumnKind.NUMBER),
            ColumnSpec.required("value_projected_month", ColumnKind.NUMBER));
    static final List<ColumnSpec> RESIDUAL_COLUMNS = List.of(
            ColumnSpec.required("cell_id", ColumnKind.INTEGER),
            ColumnSpec.required("year", ColumnKind.INTEGER),
            ColumnSpec.required("month", ColumnKind.INTEGER),
            ColumnSpec.required("value", ColumnKind.NUMBER),
            ColumnSpec.required("value_lo", ColumnKind.NUMBER),
            ColumnSpec.required("value_hi", ColumnKind.NUMBER),
            ColumnSpec.required("value_source", ColumnKind.TEXT),
            ColumnSpec.required("residual_type", ColumnKind.TEXT));

    private final DuckDb duckDb;
    private final Partitions partitions;

    HarmonizedReleaseLoader(DuckDb duckDb, Partitions partitions) {
        this.duckDb = duckDb;
        this.partitions = partitions;
    }

    @Override
    public String context() {
        return "harmonized";
    }

    @Override
    public int order() {
        return 20;
    }

    @Override
    public List<ReleaseFile> files() {
        return List.of(new ReleaseFile(AOI_MONTH, true), new ReleaseFile(COMPARE, true), new ReleaseFile(CELL_MONTH, true),
                new ReleaseFile(CELL_DAY, false), new ReleaseFile(RESIDUAL, false));
    }

    @Override
    public void validate(ReleaseSource source) {
        ValidationReport report = source.report();
        String month = source.file(AOI_MONTH).toString();
        String compare = source.file(COMPARE).toString();
        try (Connection duck = duckDb.open()) {
            ParquetColumns m = ParquetColumns.inspect(duck, month, AOI_MONTH, MONTH_COLUMNS, report);
            if (m.ok()) {
                FileChecks.codeValues(duck, month, AOI_MONTH, m, "value_source", CodeValues.VALUE_SOURCES, CodeValues.VALUE_SOURCE_V0, report);
                FileChecks.codeValues(duck, month, AOI_MONTH, m, "data_source", CodeValues.DATA_SOURCES, Map.of(), report);
                FileChecks.codeValues(duck, month, AOI_MONTH, m, "status", CodeValues.STATUSES, Map.of(), report);
                FileChecks.uniqueKey(duck, month, AOI_MONTH, m, "{aoi_id}, {year}, {month}", report);
                if (FileChecks.count(duck, month, m.render(SqlResource.text("harmonized/duck_month_checks.sql"))) > 0) {
                    report.reject("KEY_INVALID", AOI_MONTH, null);
                }
                FileChecks.dataCases(duck, month, AOI_MONTH, m.render(SqlResource.text("harmonized/duck_aoi_month_cases.sql")), report);
            }
            ParquetColumns c = ParquetColumns.inspect(duck, compare, COMPARE, COMPARE_COLUMNS, report);
            if (c.ok()) {
                FileChecks.codeValues(duck, compare, COMPARE, c, "verdict", CodeValues.VERDICTS, Map.of(), report);
                FileChecks.codeValues(duck, compare, COMPARE, c, "status", CodeValues.STATUSES, Map.of(), report);
                FileChecks.uniqueKey(duck, compare, COMPARE, c, "{aoi_id}, {year}, {month}", report);
                if (FileChecks.count(duck, compare, c.render(SqlResource.text("harmonized/duck_compare_checks.sql"))) > 0) {
                    report.reject("RANGE_INVALID", COMPARE, "percentile or key");
                }
            }
            ParquetColumns.inspect(duck, source.file(CELL_MONTH).toString(), CELL_MONTH, CENTER_COLUMNS, report);
            if (source.has(CELL_DAY)) {
                String day = source.file(CELL_DAY).toString();
                ParquetColumns d = ParquetColumns.inspect(duck, day, CELL_DAY, CELL_DAY_COLUMNS, report);
                if (d.ok()) {
                    FileChecks.codeValues(duck, day, CELL_DAY, d, "value_source", CELL_DAY_SOURCES, CodeValues.VALUE_SOURCE_V0, report);
                }
            } else {
                report.warn("FILE_ABSENT", CELL_DAY, "day-level summaries answer 503 for this release");
            }
            if (source.has(RESIDUAL)) {
                String res = source.file(RESIDUAL).toString();
                ParquetColumns r = ParquetColumns.inspect(duck, res, RESIDUAL, RESIDUAL_COLUMNS, report);
                if (r.ok()) {
                    FileChecks.codeValues(duck, res, RESIDUAL, r, "residual_type", RESIDUAL_TYPES, Map.of(), report);
                    FileChecks.codeValues(duck, res, RESIDUAL, r, "value_source", CodeValues.VALUE_SOURCES, CodeValues.VALUE_SOURCE_V0, report);
                }
            } else {
                report.warn("FILE_ABSENT", RESIDUAL, "month_residual has no per-type breakdown for this release");
            }
        } catch (SQLException e) {
            report.reject("FILE_UNREADABLE", AOI_MONTH + "|" + COMPARE, null);
        }
    }

    @Override
    public LoadResult load(ReleaseSource source) {
        long id = source.releaseId();
        Map<String, Long> rows = new LinkedHashMap<>();
        try (Connection duck = duckDb.open()) {
            ValidationReport scratch = new ValidationReport();
            ParquetColumns m = ParquetColumns.inspect(duck, source.file(AOI_MONTH).toString(), AOI_MONTH, MONTH_COLUMNS, scratch);
            rows.put(T_MONTH, copy(duck, m, source.file(AOI_MONTH).toString(), T_MONTH, id,
                    "harmonized/duck_aoi_month.sql", "harmonized/copy_aoi_month.sql", (r, line) -> line
                            .text(r.getString(1)).integer(r.getInt(2), false).integer(r.getInt(3), false)
                            .num(r.getDouble(4), r.wasNull()).num(r.getDouble(5), r.wasNull()).num(r.getDouble(6), r.wasNull())
                            .integer(r.getLong(7), r.wasNull()).integer(r.getLong(8), r.wasNull())
                            .text(CodeValues.valueSource(r.getString(9))).text(r.getString(10)).text(r.getString(11))
                            .integer(r.getLong(12), r.wasNull()).num(r.getDouble(13), r.wasNull()).text(r.getString(14)),
                    "harmonized/index_aoi_month.sql"));
            ParquetColumns c = ParquetColumns.inspect(duck, source.file(COMPARE).toString(), COMPARE, COMPARE_COLUMNS, scratch);
            rows.put(T_COMPARE, copy(duck, c, source.file(COMPARE).toString(), T_COMPARE, id,
                    "harmonized/duck_aoi_month_compare.sql", "harmonized/copy_aoi_month_compare.sql", (r, line) -> line
                            .text(r.getString(1)).integer(r.getInt(2), false).integer(r.getInt(3), false)
                            .num(r.getDouble(4), r.wasNull()).num(r.getDouble(5), r.wasNull()).num(r.getDouble(6), r.wasNull())
                            .num(r.getDouble(7), r.wasNull()).num(r.getDouble(8), r.wasNull()).num(r.getDouble(9), r.wasNull())
                            .integer(r.getInt(10), r.wasNull()).text(r.getString(11)).text(r.getString(12))
                            .bool(r.getBoolean(13), r.wasNull()).num(r.getDouble(14), r.wasNull()).text(r.getString(15))
                            .text(r.getString(16)).num(r.getDouble(17), r.wasNull()).text(r.getString(18)),
                    "harmonized/index_aoi_month.sql"));
            ParquetColumns k = ParquetColumns.inspect(duck, source.file(CELL_MONTH).toString(), CELL_MONTH, CENTER_COLUMNS, scratch);
            rows.put(T_CENTER, copy(duck, k, source.file(CELL_MONTH).toString(), T_CENTER, id,
                    "harmonized/duck_cell_centers.sql", "harmonized/copy_cell_center.sql", (r, line) -> line
                            .integer(r.getLong(1), false).real(r.getFloat(2), false).real(r.getFloat(3), false),
                    "harmonized/index_cell_center.sql"));
            return new LoadResult(rows);
        } catch (SQLException e) {
            throw new ReleaseLoadException(LoadFailures.reason(e, "harmonized"), e);
        }
    }

    private long copy(Connection duck, ParquetColumns cols, String file, String table, long id, String select, String copy,
                      CopyInputStream.RowEncoder encoder, String indexes) throws SQLException {
        partitions.create(table, id);
        long n;
        try (PreparedStatement ps = duck.prepareStatement(cols.render(SqlResource.text(select)))) {
            ps.setString(1, file);
            try (ResultSet rs = ps.executeQuery()) {
                n = partitions.copy(table, id, SqlResource.text(copy), new CopyInputStream(rs, encoder));
            }
        }
        partitions.finish(table, id, SqlResource.statements(indexes));
        return n;
    }

    @Override
    public void drop(long releaseId) {
        try {
            partitions.drop(T_MONTH, releaseId);
            partitions.drop(T_COMPARE, releaseId);
            partitions.drop(T_CENTER, releaseId);
        } catch (SQLException e) {
            throw new IllegalStateException("cannot drop harmonized partitions", e);
        }
    }
}

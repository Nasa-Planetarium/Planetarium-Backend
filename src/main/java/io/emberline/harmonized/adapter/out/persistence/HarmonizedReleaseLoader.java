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
 * as-is (A1). Missing months keep value NULL (E2); out-of-interval values are kept (E3); v0 value_source names are
 * translated to v1 and reported. The read endpoints (calendar, comparison, summary) are B2.
 */
@Component
class HarmonizedReleaseLoader implements ReleaseLoader {

    static final String AOI_MONTH = "aoi_month.parquet";
    static final String COMPARE = "aoi_month_compare.parquet";
    static final String T_MONTH = "harmonized.aoi_month";
    static final String T_COMPARE = "harmonized.aoi_month_compare";

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
            ColumnSpec.required("n_cells", ColumnKind.INTEGER));
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
            ColumnSpec.optional("low_sample", ColumnKind.BOOLEAN));

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
        return List.of(new ReleaseFile(AOI_MONTH, true), new ReleaseFile(COMPARE, true));
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
                            .integer(r.getLong(12), r.wasNull())));
            ParquetColumns c = ParquetColumns.inspect(duck, source.file(COMPARE).toString(), COMPARE, COMPARE_COLUMNS, scratch);
            rows.put(T_COMPARE, copy(duck, c, source.file(COMPARE).toString(), T_COMPARE, id,
                    "harmonized/duck_aoi_month_compare.sql", "harmonized/copy_aoi_month_compare.sql", (r, line) -> line
                            .text(r.getString(1)).integer(r.getInt(2), false).integer(r.getInt(3), false)
                            .num(r.getDouble(4), r.wasNull()).num(r.getDouble(5), r.wasNull()).num(r.getDouble(6), r.wasNull())
                            .num(r.getDouble(7), r.wasNull()).num(r.getDouble(8), r.wasNull()).num(r.getDouble(9), r.wasNull())
                            .integer(r.getInt(10), r.wasNull()).text(r.getString(11)).text(r.getString(12))
                            .bool(r.getBoolean(13), r.wasNull())));
            return new LoadResult(rows);
        } catch (SQLException e) {
            throw new ReleaseLoadException(LoadFailures.reason(e, "harmonized"), e);
        }
    }

    private long copy(Connection duck, ParquetColumns cols, String file, String table, long id, String select, String copy,
                      CopyInputStream.RowEncoder encoder) throws SQLException {
        partitions.create(table, id);
        long n;
        try (PreparedStatement ps = duck.prepareStatement(cols.render(SqlResource.text(select)))) {
            ps.setString(1, file);
            try (ResultSet rs = ps.executeQuery()) {
                n = partitions.copy(table, id, SqlResource.text(copy), new CopyInputStream(rs, encoder));
            }
        }
        partitions.finish(table, id, SqlResource.statements("harmonized/index_aoi_month.sql"));
        return n;
    }

    @Override
    public void drop(long releaseId) {
        try {
            partitions.drop(T_MONTH, releaseId);
            partitions.drop(T_COMPARE, releaseId);
        } catch (SQLException e) {
            throw new IllegalStateException("cannot drop harmonized partitions", e);
        }
    }
}

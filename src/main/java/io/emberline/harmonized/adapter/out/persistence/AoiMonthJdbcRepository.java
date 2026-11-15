package io.emberline.harmonized.adapter.out.persistence;

import io.emberline.harmonized.adapter.out.HarmonizedMetrics;
import io.emberline.harmonized.application.port.out.AoiMonthReadPort;
import io.emberline.harmonized.domain.AoiMonths;
import io.emberline.harmonized.domain.MonthRecord;
import io.emberline.shared.error.DataUnavailableException;
import io.emberline.shared.error.ErrorCode;
import io.emberline.shared.release.ReleaseRef;
import io.emberline.shared.sql.SqlResource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** One AOI's months of the pinned release: aoi_month LEFT JOIN aoi_month_compare in a single statement. */
@Repository
class AoiMonthJdbcRepository implements AoiMonthReadPort {

    private final JdbcClient jdbc;
    private final HarmonizedMetrics metrics;

    AoiMonthJdbcRepository(JdbcClient jdbc, HarmonizedMetrics metrics) {
        this.jdbc = jdbc;
        this.metrics = metrics;
    }

    @Override
    public AoiMonths load(ReleaseRef release, String aoiId, String route) {
        metrics.sqlStatement(route);
        try {
            List<MonthRecord> rows = jdbc.sql(SqlResource.text("harmonized/select_aoi_months.sql"))
                    .param("releaseId", release.releaseId()).param("aoiId", aoiId)
                    .query((rs, n) -> row(rs)).list();
            return new AoiMonths(aoiId, rows);
        } catch (DataAccessException e) {
            throw new DataUnavailableException(ErrorCode.DATA_NOT_LOADED, null, "area months are not available", e);
        }
    }

    private static MonthRecord row(ResultSet rs) throws SQLException {
        return new MonthRecord(rs.getInt("year"), rs.getInt("month"), d(rs, "value"), d(rs, "value_lo"), d(rs, "value_hi"),
                d(rs, "value_projected"), rs.getObject("as_of_date", LocalDate.class), l(rs, "raw_modis"),
                l(rs, "raw_viirs"), rs.getString("value_source"), rs.getString("data_source"), rs.getString("status"),
                i(rs, "n_cells"), d(rs, "compare_value"), d(rs, "baseline_median"), d(rs, "baseline_p25"),
                d(rs, "baseline_p75"), d(rs, "percentile"), d(rs, "ratio_to_median"), i(rs, "n_baseline_years"),
                rs.getString("verdict"), rs.getString("compare_status"), rs.getObject("low_sample", Boolean.class),
                d(rs, "compare_value_projected"), rs.getObject("compare_as_of_date", LocalDate.class),
                rs.getString("baseline_period"), d(rs, "continuity_ratio"), rs.getString("continuity_source"));
    }

    private static Double d(ResultSet rs, String column) throws SQLException {
        double v = rs.getDouble(column);
        return rs.wasNull() ? null : v;
    }

    private static Long l(ResultSet rs, String column) throws SQLException {
        long v = rs.getLong(column);
        return rs.wasNull() ? null : v;
    }

    private static Integer i(ResultSet rs, String column) throws SQLException {
        int v = rs.getInt(column);
        return rs.wasNull() ? null : v;
    }
}

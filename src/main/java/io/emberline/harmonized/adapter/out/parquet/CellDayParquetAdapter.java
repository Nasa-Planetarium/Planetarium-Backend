package io.emberline.harmonized.adapter.out.parquet;

import io.emberline.harmonized.application.port.out.CellDayQueryPort;
import io.emberline.harmonized.domain.DayTotal;
import io.emberline.shared.error.DataUnavailableException;
import io.emberline.shared.error.ErrorCode;
import io.emberline.shared.release.ReleaseRef;
import io.emberline.shared.sql.SqlResource;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Component;

/**
 * DuckDB over the pinned release's harmonized_cell_day.parquet (ruling
 * R-CD1: never loaded into PostgreSQL). (cell_id, date_local) repeats on day 1 (observed row + residual carrier), so
 * every query SUMs per group with NULLs skipped; nothing assumes one row per key.
 */
@Component
class CellDayParquetAdapter implements CellDayQueryPort {

    private final ParquetReader reader;
    private final ReleaseFiles files;

    CellDayParquetAdapter(ParquetReader reader, ReleaseFiles files) {
        this.reader = reader;
        this.files = files;
    }

    @Override
    public boolean available(ReleaseRef release) {
        return files.find(release, ReleaseFiles.CELL_DAY).isPresent();
    }

    @Override
    public List<DayTotal> dayTotals(ReleaseRef release, long[] cells, LocalDate from, LocalDate to) {
        String file = files.require(release, ReleaseFiles.CELL_DAY).toString();
        try (Connection c = reader.open()) {
            ParquetReader.cells(c, cells);
            try (PreparedStatement ps = c.prepareStatement(SqlResource.text("harmonized/duck_cell_day_totals.sql"))) {
                ps.setString(1, file);
                ps.setDate(2, Date.valueOf(from));
                ps.setDate(3, Date.valueOf(to));
                List<DayTotal> out = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new DayTotal(LocalDate.parse(rs.getString(1)), rs.getDouble(2), nullable(rs, 3),
                                nullable(rs, 4), rs.getLong(5), rs.getString(6)));
                    }
                }
                return out;
            }
        } catch (SQLException e) {
            throw unavailable(e);
        }
    }

    @Override
    public Map<YearMonth, Double> monthResiduals(ReleaseRef release, long[] cells, YearMonth from, YearMonth to) {
        String file = files.require(release, ReleaseFiles.CELL_DAY).toString();
        try (Connection c = reader.open()) {
            ParquetReader.cells(c, cells);
            try (PreparedStatement ps = c.prepareStatement(SqlResource.text("harmonized/duck_cell_day_residuals.sql"))) {
                ps.setString(1, file);
                ps.setDate(2, Date.valueOf(from.atDay(1)));
                ps.setDate(3, Date.valueOf(to.atEndOfMonth()));
                Map<YearMonth, Double> out = new TreeMap<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.put(YearMonth.of(rs.getInt(1), rs.getInt(2)), rs.getDouble(3));
                    }
                }
                return out;
            }
        } catch (SQLException e) {
            throw unavailable(e);
        }
    }

    private static Double nullable(ResultSet rs, int i) throws SQLException {
        double v = rs.getDouble(i);
        return rs.wasNull() ? null : v;
    }

    static DataUnavailableException unavailable(SQLException e) {
        return new DataUnavailableException(ErrorCode.DATA_NOT_LOADED, null, "day-level data could not be read", e);
    }
}

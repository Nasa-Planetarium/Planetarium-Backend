package io.emberline.harmonized.adapter.out.parquet;

import io.emberline.harmonized.application.port.out.ResidualQueryPort;
import io.emberline.shared.release.ReleaseRef;
import io.emberline.shared.sql.SqlResource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Component;

/** DuckDB over the pinned release's harmonized_residual_cell_month.parquet (ruling R-CD1: read in place). */
@Component
class ResidualParquetAdapter implements ResidualQueryPort {

    private final ParquetReader reader;
    private final ReleaseFiles files;

    ResidualParquetAdapter(ParquetReader reader, ReleaseFiles files) {
        this.reader = reader;
        this.files = files;
    }

    @Override
    public boolean available(ReleaseRef release) {
        return files.find(release, ReleaseFiles.RESIDUAL).isPresent();
    }

    @Override
    public Map<YearMonth, Map<String, Double>> residualsByType(ReleaseRef release, long[] cells, YearMonth from, YearMonth to) {
        String file = files.require(release, ReleaseFiles.RESIDUAL).toString();
        try (Connection c = reader.open()) {
            ParquetReader.cells(c, cells);
            try (PreparedStatement ps = c.prepareStatement(SqlResource.text("harmonized/duck_residual_by_type.sql"))) {
                ps.setString(1, file);
                ps.setInt(2, from.getYear() * 100 + from.getMonthValue());
                ps.setInt(3, to.getYear() * 100 + to.getMonthValue());
                Map<YearMonth, Map<String, Double>> out = new TreeMap<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.computeIfAbsent(YearMonth.of(rs.getInt(1), rs.getInt(2)), k -> new LinkedHashMap<>())
                                .put(rs.getString(3), rs.getDouble(4));
                    }
                }
                return out;
            }
        } catch (SQLException e) {
            throw CellDayParquetAdapter.unavailable(e);
        }
    }
}

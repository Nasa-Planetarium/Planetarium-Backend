package io.emberline.outlook.adapter.out.persistence;

import io.emberline.outlook.application.port.out.OccurrenceReadPort;
import io.emberline.outlook.domain.AoiLayer;
import io.emberline.outlook.domain.AoiRow;
import io.emberline.outlook.domain.GridCell;
import io.emberline.outlook.domain.GridLayer;
import io.emberline.shared.error.DataUnavailableException;
import io.emberline.shared.error.ErrorCode;
import io.emberline.shared.sql.SqlResource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Reads the outlook partitions of a release (the loader refused more than one head value, so the first row's is the file's). */
@Repository
class OccurrenceJdbcRepository implements OccurrenceReadPort {

    /** The head of a file and its rows. */
    private record Head(String targetMonth, String asOfDate, String model) {
    }

    private final JdbcClient jdbc;

    OccurrenceJdbcRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<GridLayer> grid(long releaseId) {
        List<Head> heads = new ArrayList<>(1);
        List<GridCell> cells = translate(() -> jdbc.sql(SqlResource.text("outlook/select_grid_occurrence.sql"))
                .param("releaseId", releaseId)
                .query((rs, n) -> {
                    if (n == 0) {
                        heads.add(new Head(rs.getString("target_month"), rs.getString("as_of_date"), rs.getString("model")));
                    }
                    return new GridCell(rs.getLong("cell_id"), rs.getDouble("lat"), rs.getDouble("lon"),
                            rs.getDouble("p_any"), nullableDouble(rs, "p_none"), rs.getDouble("baseline_p"),
                            rs.getBoolean("persistent_source"), rs.getString("adm0"), rs.getString("continent"),
                            nullableDouble(rs, "rate_5y"), nullableBoolean(rs, "lag1_projected"));
                }).list());
        return heads.isEmpty() ? Optional.empty()
                : Optional.of(new GridLayer(heads.get(0).targetMonth(), heads.get(0).asOfDate(), heads.get(0).model(), cells));
    }

    @Override
    public Optional<AoiLayer> aois(long releaseId) {
        List<AoiRow> rows = translate(() -> jdbc.sql(SqlResource.text("outlook/select_aoi_occurrence.sql"))
                .param("releaseId", releaseId)
                .query((rs, n) -> new AoiRow(rs.getString("aoi_id"), rs.getString("target_month"), rs.getDouble("p_any"),
                        nullableDouble(rs, "p_none"), nullableDouble(rs, "baseline_p"), rs.getString("model"),
                        rs.getString("as_of_date"), rs.getBoolean("suspected_industrial"), nullableDouble(rs, "rate_5y"),
                        nullableInteger(rs, "n_years"), nullableBoolean(rs, "lag1_projected"))).list());
        return rows.isEmpty() ? Optional.empty()
                : Optional.of(new AoiLayer(rows.get(0).targetMonth(), rows.get(0).asOfDate(), rows));
    }

    private static Double nullableDouble(ResultSet rs, String column) throws SQLException {
        double v = rs.getDouble(column);
        return rs.wasNull() ? null : v;
    }

    private static Integer nullableInteger(ResultSet rs, String column) throws SQLException {
        int v = rs.getInt(column);
        return rs.wasNull() ? null : v;
    }

    private static Boolean nullableBoolean(ResultSet rs, String column) throws SQLException {
        boolean v = rs.getBoolean(column);
        return rs.wasNull() ? null : v;
    }

    private static <T> T translate(Supplier<T> query) {
        try {
            return query.get();
        } catch (DataAccessException e) {
            throw new DataUnavailableException(ErrorCode.DATA_NOT_LOADED, null, "occurrence layers are not available", e);
        }
    }
}

package io.emberline.observation.adapter.out.persistence;

import io.emberline.observation.application.port.out.CellReadPort;
import io.emberline.observation.domain.CellBox;
import io.emberline.observation.domain.CellRow;
import io.emberline.shared.error.DataUnavailableException;
import io.emberline.shared.error.ErrorCode;
import io.emberline.shared.sql.SqlResource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class CellJdbcRepository implements CellReadPort {

    private final JdbcClient jdbc;

    CellJdbcRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<CellRow> cellsInBox(long releaseId, int year, int month, CellBox box, int limit) {
        return translate(() -> jdbc.sql(SqlResource.text("observation/select_cells_bbox.sql"))
                .param("releaseId", releaseId).param("year", year).param("month", month)
                .param("south", box.south()).param("north", box.north()).param("west", box.west()).param("east", box.east())
                .param("limit", limit)
                .query((rs, n) -> row(rs)).list());
    }

    @Override
    public Optional<String> monthStatus(long releaseId, int year, int month) {
        return translate(() -> jdbc.sql(SqlResource.text("observation/select_cell_month_status.sql"))
                .param("releaseId", releaseId).param("year", year).param("month", month)
                .query(String.class).optional());
    }

    private static CellRow row(ResultSet rs) throws SQLException {
        return new CellRow(rs.getLong("cell_id"), rs.getFloat("cell_lat"), rs.getFloat("cell_lon"),
                rs.getObject("value", Double.class), rs.getObject("value_lo", Double.class),
                rs.getObject("value_hi", Double.class), rs.getObject("raw_modis", Integer.class),
                rs.getObject("raw_viirs", Integer.class), rs.getString("value_source"), rs.getString("data_source"),
                rs.getString("status"), rs.getObject("value_projected", Double.class),
                rs.getObject("as_of_date", LocalDate.class));
    }

    private static <T> T translate(Supplier<T> query) {
        try {
            return query.get();
        } catch (DataAccessException e) {
            throw new DataUnavailableException(ErrorCode.DATA_NOT_LOADED, null, "cell data is not available right now", e);
        }
    }
}

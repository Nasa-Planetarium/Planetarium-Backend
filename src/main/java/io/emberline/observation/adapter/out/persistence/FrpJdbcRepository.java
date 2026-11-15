package io.emberline.observation.adapter.out.persistence;

import io.emberline.observation.application.port.out.FrpReadPort;
import io.emberline.observation.domain.CellIdRange;
import io.emberline.observation.domain.FrpRow;
import io.emberline.shared.error.DataUnavailableException;
import io.emberline.shared.error.ErrorCode;
import io.emberline.shared.sql.SqlResource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** observation.cell_day_frp: day rows and cell-month groups inside a grid range (and a cell set for an AOI). */
@Repository
class FrpJdbcRepository implements FrpReadPort {

    private final JdbcClient jdbc;
    /** A loaded release never changes, so whether it has FRP rows is asked once per release. */
    private final Map<Long, Boolean> loaded = new ConcurrentHashMap<>();

    FrpJdbcRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean loaded(long releaseId) {
        Boolean known = loaded.get(releaseId);
        if (known != null) {
            return known;
        }
        boolean has = translate(() -> jdbc.sql(SqlResource.text("observation/select_frp_loaded.sql"))
                .param("releaseId", releaseId).query(Boolean.class).single());
        loaded.put(releaseId, has);
        return has;
    }

    @Override
    public List<FrpRow> days(long releaseId, LocalDate start, LocalDate end, CellIdRange range, long[] cells, int limit) {
        return rows(cells == null ? "observation/select_frp_day.sql" : "observation/select_frp_day_cells.sql",
                releaseId, start, end, range, cells, limit);
    }

    @Override
    public List<FrpRow> months(long releaseId, LocalDate start, LocalDate end, CellIdRange range, long[] cells, int limit) {
        return rows(cells == null ? "observation/select_frp_month.sql" : "observation/select_frp_month_cells.sql",
                releaseId, start, end, range, cells, limit);
    }

    private List<FrpRow> rows(String sql, long releaseId, LocalDate start, LocalDate end, CellIdRange range, long[] cells,
                              int limit) {
        return translate(() -> {
            JdbcClient.StatementSpec spec = jdbc.sql(SqlResource.text(sql))
                    .param("releaseId", releaseId).param("start", start).param("end", end)
                    .param("idLo", range.idLo()).param("idHi", range.idHi())
                    .param("rowLo", range.rowLo()).param("rowHi", range.rowHi())
                    .param("colLo", range.colLo()).param("colHi", range.colHi())
                    .param("limit", limit);
            if (cells != null) {
                spec = spec.param("cells", arrayLiteral(cells));
            }
            return spec.query((rs, n) -> row(rs)).list();
        });
    }

    /** PostgreSQL array literal {@code {id,id,…}} of the cell set (bound as text, cast to bigint[] in the SQL). */
    static String arrayLiteral(long[] cells) {
        return Arrays.stream(cells).mapToObj(Long::toString).collect(Collectors.joining(",", "{", "}"));
    }

    /** Columns 1–6: cell_id, date (date_local or month start), frp_sum, frp_snpp_equiv_sum, frp_snpp_equiv_max, n_points. */
    private static FrpRow row(ResultSet rs) throws SQLException {
        float max = rs.getFloat(5);
        Float frpSnppEquivMax = rs.wasNull() ? null : max;
        return new FrpRow(rs.getLong(1), rs.getObject(2, LocalDate.class), rs.getObject(3, Double.class),
                rs.getObject(4, Double.class), frpSnppEquivMax, rs.getLong(6));
    }

    private static <T> T translate(Supplier<T> query) {
        try {
            return query.get();
        } catch (DataAccessException e) {
            throw new DataUnavailableException(ErrorCode.DATA_NOT_LOADED, null, "FRP data is not available right now", e);
        }
    }
}

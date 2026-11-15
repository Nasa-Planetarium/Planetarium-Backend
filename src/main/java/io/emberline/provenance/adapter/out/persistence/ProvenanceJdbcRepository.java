package io.emberline.provenance.adapter.out.persistence;

import io.emberline.provenance.application.port.out.ProvenanceReadPort;
import io.emberline.provenance.domain.MethodMeta;
import io.emberline.provenance.domain.SatelliteDay;
import io.emberline.provenance.domain.SatelliteMonth;
import io.emberline.shared.error.DataUnavailableException;
import io.emberline.shared.error.ErrorCode;
import io.emberline.shared.sql.SqlResource;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class ProvenanceJdbcRepository implements ProvenanceReadPort {

    private final JdbcClient jdbc;

    ProvenanceJdbcRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<MethodMeta> methodMeta(long releaseId) {
        return translate(() -> jdbc.sql(SqlResource.text("provenance/select_method_meta.sql")).param("releaseId", releaseId)
                .query((rs, n) -> new MethodMeta(rs.getString("method_version"), rs.getString("meta"),
                        rs.getString("aliases_used"), rs.getString("warnings"), rs.getString("files"))).optional());
    }

    @Override
    public List<SatelliteDay> satelliteDays(long releaseId, LocalDate start, LocalDate end) {
        return translate(() -> jdbc.sql(SqlResource.text("provenance/select_satellite_day.sql")).param("releaseId", releaseId)
                .param("start", start).param("end", end)
                .query((rs, n) -> {
                    Timestamp up = rs.getTimestamp("updated_at");
                    return new SatelliteDay(rs.getString("satellite"), rs.getObject("date", LocalDate.class),
                            rs.getString("status"), rs.getString("data_source"), up == null ? null : up.toInstant(),
                            rs.getString("note"), rs.getObject("n_det_global", Long.class), rs.getString("coverage"),
                            rs.getObject("ratio_to_seasonal", Double.class));
                }).list());
    }

    @Override
    public Optional<LocalDate> satelliteDayThrough(long releaseId) {
        List<LocalDate> through = translate(() -> jdbc.sql(SqlResource.text("provenance/select_satellite_day_through.sql"))
                .param("releaseId", releaseId).query((rs, n) -> rs.getObject(1, LocalDate.class)).list());
        return through.isEmpty() ? Optional.empty() : Optional.ofNullable(through.get(0));
    }

    @Override
    public List<SatelliteMonth> satelliteMonths(long releaseId) {
        return translate(() -> jdbc.sql(SqlResource.text("provenance/select_satellite_month.sql")).param("releaseId", releaseId)
                .query((rs, n) -> new SatelliteMonth(rs.getString("satellite"), rs.getInt("year"), rs.getInt("month"),
                        rs.getObject("obs_days", Integer.class), rs.getObject("low_days", Integer.class),
                        rs.getObject("missing_days", Integer.class), rs.getObject("days_in_month", Integer.class),
                        rs.getObject("first_day", LocalDate.class), rs.getObject("last_day", LocalDate.class),
                        rs.getString("month_status"))).list());
    }

    private static <T> T translate(Supplier<T> query) {
        try {
            return query.get();
        } catch (DataAccessException e) {
            throw new DataUnavailableException(ErrorCode.DATA_NOT_LOADED, null, "release provenance is not available", e);
        }
    }
}

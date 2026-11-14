package io.emberline.provenance.adapter.out.persistence;

import io.emberline.provenance.application.port.out.ProvenanceReadPort;
import io.emberline.provenance.domain.MethodMeta;
import io.emberline.provenance.domain.MonthStatus;
import io.emberline.provenance.domain.SatelliteDay;
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
    public List<MonthStatus> monthStatus(long releaseId) {
        return translate(() -> jdbc.sql(SqlResource.text("provenance/select_month_status.sql")).param("releaseId", releaseId)
                .query((rs, n) -> new MonthStatus(rs.getInt("year"), rs.getInt("month"), rs.getString("value_source"),
                        rs.getString("data_source"), rs.getString("status"))).list());
    }

    @Override
    public List<SatelliteDay> satelliteDays(long releaseId, LocalDate start, LocalDate end) {
        return translate(() -> jdbc.sql(SqlResource.text("provenance/select_satellite_day.sql")).param("releaseId", releaseId)
                .param("start", start).param("end", end)
                .query((rs, n) -> {
                    Timestamp up = rs.getTimestamp("updated_at");
                    return new SatelliteDay(rs.getString("satellite"), rs.getObject("date", LocalDate.class),
                            rs.getString("status"), rs.getString("data_source"), up == null ? null : up.toInstant());
                }).list());
    }

    @Override
    public boolean hasSatelliteDays(long releaseId) {
        return translate(() -> jdbc.sql(SqlResource.text("provenance/select_satellite_day_exists.sql"))
                .param("releaseId", releaseId).query(Boolean.class).single());
    }

    private static <T> T translate(Supplier<T> query) {
        try {
            return query.get();
        } catch (DataAccessException e) {
            throw new DataUnavailableException(ErrorCode.DATA_NOT_LOADED, null, "release provenance is not available", e);
        }
    }
}

package io.emberline.ingest.adapter.out.persistence;

import io.emberline.ingest.application.port.out.ActiveReleasePort;
import io.emberline.shared.release.ReleaseRef;
import io.emberline.shared.sql.SqlResource;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class ActiveReleaseJdbcRepository implements ActiveReleasePort {

    private final JdbcClient jdbc;

    ActiveReleaseJdbcRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<ReleaseRef> current() {
        return jdbc.sql(SqlResource.text("ingest/active_release_select.sql")).query((rs, n) -> {
            Timestamp loaded = rs.getTimestamp("loaded_at");
            return new ReleaseRef(rs.getLong("id"), rs.getString("release_key"), rs.getString("method_version"),
                    rs.getString("data_version"), loaded == null ? null : loaded.toInstant());
        }).optional();
    }

    @Override
    public void point(long releaseId, Instant at) {
        jdbc.sql(SqlResource.text("ingest/active_release_upsert.sql")).param("releaseId", releaseId)
                .param("at", at.atOffset(ZoneOffset.UTC)).update();
    }
}

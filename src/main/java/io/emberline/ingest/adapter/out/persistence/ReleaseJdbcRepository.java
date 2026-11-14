package io.emberline.ingest.adapter.out.persistence;

import io.emberline.ingest.application.port.out.ReleaseRepository;
import io.emberline.ingest.domain.Release;
import io.emberline.ingest.domain.ReleaseKey;
import io.emberline.ingest.domain.ReleaseState;
import io.emberline.shared.error.ConflictException;
import io.emberline.shared.error.ErrorCode;
import io.emberline.shared.sql.SqlResource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** ingest.release through JdbcClient (no JPA, S7); optimistic lock on {@code version}. */
@Repository
class ReleaseJdbcRepository implements ReleaseRepository {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {
    };

    private final JdbcClient jdbc;

    ReleaseJdbcRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Release> findByKey(ReleaseKey key) {
        return jdbc.sql(SqlResource.text("ingest/release_by_key.sql")).param("releaseKey", key.value())
                .query(ReleaseJdbcRepository::map).optional();
    }

    @Override
    public List<Release> findLoading() {
        return jdbc.sql(SqlResource.text("ingest/release_by_state.sql")).param("state", ReleaseState.LOADING.name())
                .query(ReleaseJdbcRepository::map).list();
    }

    @Override
    public Optional<Release> findActive() {
        return jdbc.sql(SqlResource.text("ingest/release_by_state.sql")).param("state", ReleaseState.ACTIVE.name())
                .query(ReleaseJdbcRepository::map).optional();
    }

    @Override
    public Release insert(Release release) {
        long id = jdbc.sql(SqlResource.text("ingest/release_insert.sql"))
                .param("releaseKey", release.key().value())
                .param("state", release.state().name())
                .param("stagedAt", utc(release.stagedAt()))
                .query(Long.class).single();
        return release.withId(id);
    }

    @Override
    public void update(Release r) {
        int n = jdbc.sql(SqlResource.text("ingest/release_update.sql"))
                .param("id", r.id())
                .param("version", r.version())
                .param("state", r.state().name())
                .param("methodVersion", r.methodVersion())
                .param("dataVersion", r.dataVersion())
                .param("manifestSha256", r.manifestSha256())
                .param("reasonCodes", JSON.writeValueAsString(r.reasonCodes()))
                .param("aliasesUsed", JSON.writeValueAsString(r.aliasesUsed()))
                .param("warnings", JSON.writeValueAsString(r.warnings()))
                .param("loadedAt", utc(r.loadedAt()))
                .param("activatedAt", utc(r.activatedAt()))
                .update();
        if (n != 1) {
            throw new ConflictException(ErrorCode.RELEASE_STATE_CONFLICT, "release_key",
                    "release " + r.key() + " changed concurrently");
        }
    }

    private static OffsetDateTime utc(Instant i) {
        return i == null ? null : i.atOffset(ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }

    private static Release map(ResultSet rs, int row) throws SQLException {
        return new Release(rs.getLong("id"), new ReleaseKey(rs.getString("release_key")),
                ReleaseState.valueOf(rs.getString("state")), rs.getString("method_version"), rs.getString("data_version"),
                rs.getString("manifest_sha256"), JSON.readValue(rs.getString("reason_codes"), STRINGS),
                JSON.readValue(rs.getString("aliases_used"), STRINGS), JSON.readValue(rs.getString("warnings"), STRINGS),
                rs.getLong("version"), instant(rs, "staged_at"), instant(rs, "loaded_at"), instant(rs, "activated_at"));
    }
}

package io.emberline.ingest.adapter.out.persistence;

import io.emberline.ingest.application.port.out.LoadTaskPort;
import io.emberline.shared.sql.SqlResource;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

@Repository
class LoadTaskJdbcRepository implements LoadTaskPort {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final JdbcClient jdbc;

    LoadTaskJdbcRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void started(long releaseId, String context) {
        jdbc.sql(SqlResource.text("ingest/load_task_start.sql")).param("releaseId", releaseId).param("context", context).update();
    }

    @Override
    public void loaded(long releaseId, String context, Map<String, Long> rows) {
        finish(releaseId, context, "LOADED", JSON.writeValueAsString(rows), null);
    }

    @Override
    public void failed(long releaseId, String context, String reason) {
        finish(releaseId, context, "FAILED", "{}", reason);
    }

    private void finish(long releaseId, String context, String state, String rows, String error) {
        jdbc.sql(SqlResource.text("ingest/load_task_finish.sql")).param("releaseId", releaseId).param("context", context)
                .param("state", state).param("rows", rows).param("errorCode", error).update();
    }
}

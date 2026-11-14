package io.emberline.platform.events;

import io.emberline.shared.sql.SqlResource;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** An envelope is handed to handlers only if its eventId is a publication stored in platform.event_publication. */
@Component
class EnvelopeVerifier {

    private final JdbcClient jdbc;

    EnvelopeVerifier(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    boolean isStoredPublication(String eventId) {
        return Boolean.TRUE.equals(jdbc.sql(SqlResource.text("platform/select_publication_exists.sql"))
                .param("id", UUID.fromString(eventId)).query(Boolean.class).single());
    }
}

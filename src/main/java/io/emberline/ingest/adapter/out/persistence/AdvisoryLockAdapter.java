package io.emberline.ingest.adapter.out.persistence;

import io.emberline.ingest.application.port.out.IngestLockPort;
import io.emberline.shared.sql.SqlResource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.stereotype.Component;

/** PostgreSQL session advisory lock on a dedicated connection: released on close or when the process dies. */
@Component
class AdvisoryLockAdapter implements IngestLockPort {

    private final DataSource dataSource;

    AdvisoryLockAdapter(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public Optional<Lock> tryAcquire() {
        try {
            Connection c = dataSource.getConnection();
            try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(SqlResource.text("ingest/lock_try.sql"))) {
                if (rs.next() && rs.getBoolean(1)) {
                    return Optional.of(() -> release(c));
                }
            }
            c.close();
            return Optional.empty();
        } catch (SQLException e) {
            throw new IllegalStateException("cannot take the ingest lock", e);
        }
    }

    private static void release(Connection c) {
        try (c; Statement s = c.createStatement()) {
            s.execute(SqlResource.text("ingest/lock_release.sql"));
        } catch (SQLException e) {
            // closing the session releases the lock anyway
        }
    }
}

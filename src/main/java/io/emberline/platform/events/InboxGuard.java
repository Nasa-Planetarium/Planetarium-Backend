package io.emberline.platform.events;

import io.emberline.shared.sql.SqlResource;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Exactly-once handling per (consumer, eventId): the inbox row (INSERT … ON CONFLICT DO NOTHING) and the handler run
 * in one transaction, so a handler failure also forgets the inbox row and the entry can be retried.
 */
@Component
class InboxGuard {

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;

    InboxGuard(JdbcClient jdbc, PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactions);
    }

    /** Runs {@code handler} unless this consumer already handled the event; true when it ran. */
    boolean once(String consumer, String eventId, Runnable handler) {
        return Boolean.TRUE.equals(tx.execute(status -> {
            int inserted = jdbc.sql(SqlResource.text("platform/insert_processed_event.sql"))
                    .param("consumer", consumer).param("eventId", UUID.fromString(eventId)).update();
            if (inserted == 0) {
                return false;
            }
            handler.run();
            return true;
        }));
    }
}

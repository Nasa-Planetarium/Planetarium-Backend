package io.emberline.platform.events;

import io.emberline.ingest.api.ReleaseActivated;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Transport end of {@code release.activated}: the Modulith publication registry (schema platform) records the event
 * in the activation transaction and marks it complete when this listener returns. B4 replaces the log line with the
 * Redis Streams XADD; until then pods pick the switch up by polling ingest.active_release.
 */
@Component
class ReleaseActivatedRelay {

    private static final Logger log = LoggerFactory.getLogger(ReleaseActivatedRelay.class);

    @TransactionalEventListener
    void on(ReleaseActivated event) {
        log.info("release.activated release_id={} release_key={} method_version={} data_version={} previous={}",
                event.releaseId(), event.releaseKey(), event.methodVersion(), event.dataVersion(), event.previousReleaseId());
    }
}

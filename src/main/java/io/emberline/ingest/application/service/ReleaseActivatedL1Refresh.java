package io.emberline.ingest.application.service;

import io.emberline.ingest.api.ReleaseActivated;
import io.emberline.shared.event.EventEnvelope;
import io.emberline.shared.event.StreamConsumer;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;

/**
 * l1-refresh (broadcast: every pod): a release.activated from another process re-reads the release pointer from
 * the database — the event is only a hint, ingest.active_release decides. Polling stays as the fallback.
 */
@Component
@StreamConsumer(value = "l1-refresh", broadcast = true)
class ReleaseActivatedL1Refresh implements Consumer<EventEnvelope<ReleaseActivated>> {

    private final ActiveReleaseProvider provider;

    ReleaseActivatedL1Refresh(ActiveReleaseProvider provider) {
        this.provider = provider;
    }

    @Override
    public void accept(EventEnvelope<ReleaseActivated> envelope) {
        provider.refresh();
    }
}

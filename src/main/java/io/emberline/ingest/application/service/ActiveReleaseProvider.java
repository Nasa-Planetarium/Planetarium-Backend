package io.emberline.ingest.application.service;

import io.emberline.ingest.api.ReleaseActivated;
import io.emberline.ingest.application.port.out.ActiveReleasePort;
import io.emberline.shared.release.ActiveReleaseSource;
import io.emberline.shared.release.ActiveReleaseSwitched;
import io.emberline.shared.release.ReleaseRef;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Pod-local active release: read once on first use, then refreshed every {@code emberline.release.poll-interval}
 * (≤ 30 s, default 10 s) and right after a ReleaseActivated committed in this JVM. A failed refresh keeps the last value.
 * Each new value (the first one read, then every switch) is announced in this JVM as {@link ActiveReleaseSwitched}.
 */
@Service
class ActiveReleaseProvider implements ActiveReleaseSource {

    private static final Logger log = LoggerFactory.getLogger(ActiveReleaseProvider.class);

    private final ActiveReleasePort port;
    private final ApplicationEventPublisher events;
    private final AtomicReference<Optional<ReleaseRef>> current = new AtomicReference<>();

    ActiveReleaseProvider(ActiveReleasePort port, ApplicationEventPublisher events) {
        this.port = port;
        this.events = events;
    }

    @Override
    public Optional<ReleaseRef> current() {
        Optional<ReleaseRef> value = current.get();
        if (value == null) {
            refresh();
            value = current.get();
        }
        return value == null ? Optional.empty() : value;
    }

    @Scheduled(fixedDelayString = "${emberline.release.poll-interval:10s}", initialDelayString = "${emberline.release.poll-interval:10s}")
    void poll() {
        refresh();
    }

    @TransactionalEventListener
    void on(ReleaseActivated event) {
        refresh();
    }

    void refresh() {
        Optional<ReleaseRef> next;
        Optional<ReleaseRef> before;
        try {
            next = port.current();
            before = current.getAndSet(next);
        } catch (RuntimeException e) {
            log.warn("active release refresh failed; keeping the previous value");
            return;
        }
        if (before != null && !before.equals(next)) {
            log.info("active release switched to {}", next.map(ReleaseRef::dataVersion).orElse("none"));
        }
        if (next.isPresent() && !next.equals(before)) {
            events.publishEvent(new ActiveReleaseSwitched(next.get()));
        }
    }
}

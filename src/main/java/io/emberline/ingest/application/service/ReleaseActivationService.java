package io.emberline.ingest.application.service;

import io.emberline.ingest.api.ReleaseActivated;
import io.emberline.ingest.application.port.in.ActivateReleaseUseCase;
import io.emberline.ingest.application.port.in.IngestOutcome;
import io.emberline.ingest.application.port.out.ActiveReleasePort;
import io.emberline.ingest.application.port.out.ReleaseFolderPort;
import io.emberline.ingest.application.port.out.ReleaseRepository;
import io.emberline.ingest.domain.Release;
import io.emberline.ingest.domain.ReleaseKey;
import io.emberline.ingest.domain.ReleaseState;
import io.emberline.shared.error.ErrorCode;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * activate (and rollback = activate an older LOADED/RETIRED release): the manifest hash given on a separate channel
 * must equal the staged and the current SHA256SUMS hash, otherwise the release is REJECTED (exit 4). One transaction:
 * previous ACTIVE → RETIRED, this → ACTIVE, ingest.active_release, publishEvent(ReleaseActivated).
 */
@Service
class ReleaseActivationService implements ActivateReleaseUseCase {

    private static final Logger log = LoggerFactory.getLogger(ReleaseActivationService.class);

    private final ReleaseRepository releases;
    private final ActiveReleasePort active;
    private final ReleaseFolderPort folders;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    ReleaseActivationService(ReleaseRepository releases, ActiveReleasePort active, ReleaseFolderPort folders,
                             ApplicationEventPublisher events, Optional<Clock> clock) {
        this.releases = releases;
        this.active = active;
        this.folders = folders;
        this.events = events;
        this.clock = clock.orElse(Clock.systemUTC());
    }

    @Override
    @Transactional
    public IngestOutcome activate(String rawKey, String expected) {
        ReleaseKey key = new ReleaseKey(rawKey);
        if (expected == null || !expected.matches("^[0-9a-fA-F]{64}$")) {
            return Outcomes.failed(key.value(), "UNCHANGED", ErrorCode.RELEASE_INVALID, List.of("EXPECT_SHA256_INVALID"));
        }
        Optional<Release> found = releases.findByKey(key);
        if (found.isEmpty()) {
            return Outcomes.failed(key.value(), "ABSENT", ErrorCode.RELEASE_STATE_CONFLICT, List.of("RELEASE_NOT_STAGED"));
        }
        Release release = found.get();
        String current = folders.resolve(key).map(dir -> dir.resolve("SHA256SUMS"))
                .filter(java.nio.file.Files::isRegularFile).map(folders::sha256).orElse("");
        if (!release.manifestMatches(expected, current)) {
            if (release.state().canMoveTo(ReleaseState.REJECTED)) {
                release.reject(List.of("RELEASE_HASH_MISMATCH"));
                releases.update(release);
                release = releases.findByKey(key).orElseThrow();
            }
            log.warn("release {} activation refused: manifest sha256 mismatch", key);
            return Outcomes.of(release, Map.of(), Outcomes.exit(ErrorCode.RELEASE_HASH_MISMATCH))
                    .withReasons(List.of("RELEASE_HASH_MISMATCH"));
        }
        if (release.state() == ReleaseState.ACTIVE) {
            return Outcomes.of(release, Map.of(), 0);
        }
        if (!release.state().canMoveTo(ReleaseState.ACTIVE)) {
            return Outcomes.of(release, Map.of(), Outcomes.exit(ErrorCode.RELEASE_STATE_CONFLICT))
                    .withReasons(List.of("RELEASE_STATE_CONFLICT:" + release.state()));
        }
        Long previous = null;
        Optional<Release> current0 = releases.findActive();
        if (current0.isPresent()) {
            Release prev = current0.get();
            prev.retire();
            releases.update(prev);
            previous = prev.id();
        }
        release.activate(clock.instant());
        releases.update(release);
        active.point(release.id(), clock.instant());
        events.publishEvent(new ReleaseActivated(release.id(), key.value(), release.methodVersion(), release.dataVersion(), previous));
        log.info("release {} activated (previous {})", key, previous);
        return Outcomes.of(releases.findByKey(key).orElseThrow(), Map.of(), 0);
    }
}

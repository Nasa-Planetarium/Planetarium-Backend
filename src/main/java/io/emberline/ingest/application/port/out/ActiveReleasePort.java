package io.emberline.ingest.application.port.out;

import io.emberline.shared.release.ReleaseRef;
import java.time.Instant;
import java.util.Optional;

/** ingest.active_release (one row). */
public interface ActiveReleasePort {

    Optional<ReleaseRef> current();

    void point(long releaseId, Instant at);
}

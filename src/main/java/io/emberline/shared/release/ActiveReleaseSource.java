package io.emberline.shared.release;

import java.util.Optional;

/** Pod-local view of {@code ingest.active_release} (refreshed by polling and by ReleaseActivated events). */
public interface ActiveReleaseSource {

    Optional<ReleaseRef> current();
}

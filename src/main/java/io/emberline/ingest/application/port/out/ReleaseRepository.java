package io.emberline.ingest.application.port.out;

import io.emberline.ingest.domain.Release;
import io.emberline.ingest.domain.ReleaseKey;
import java.util.List;
import java.util.Optional;

/** ingest.release; {@link #update} fails with RELEASE_STATE_CONFLICT when the version changed meanwhile. */
public interface ReleaseRepository {

    Optional<Release> findByKey(ReleaseKey key);

    List<Release> findLoading();

    Optional<Release> findActive();

    Release insert(Release release);

    void update(Release release);
}

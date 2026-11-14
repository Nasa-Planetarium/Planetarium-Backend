package io.emberline.provenance.application.port.in;

import io.emberline.shared.release.PinnedRelease;

/** GET /api/v1/version — API version, the active release (or null) and the public limits. */
public interface GetVersionUseCase {

    VersionResponse version(PinnedRelease release);
}

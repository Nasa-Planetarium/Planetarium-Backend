package io.emberline.shared.release;

import java.util.Optional;

/**
 * Controller argument for routes that answer with or without an active release (e.g. /version).
 * Routes that need data take {@link ReleaseRef} directly and answer 503 data_not_loaded when none is active.
 */
public record PinnedRelease(ReleaseRef ref) {

    public Optional<ReleaseRef> asOptional() {
        return Optional.ofNullable(ref);
    }
}

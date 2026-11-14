package io.emberline.shared.release;

import java.time.Instant;
import java.util.Objects;

/**
 * The release a request is pinned to (chosen once at request start). Every release-table query binds
 * {@code release_id = :releaseId}, so one response never mixes two releases.
 *
 * @param releaseId     partition value of every release table
 * @param releaseKey    folder name under EMBERLINE_RELEASE_ROOT
 * @param methodVersion m2v_meta.json method_version (data version of the model, not the API version)
 * @param dataVersion   identifier of the loaded file set ({@code <release_key>#<sha256(SHA256SUMS)[0:12]>})
 * @param loadedAt      when the release finished loading
 */
public record ReleaseRef(long releaseId, String releaseKey, String methodVersion, String dataVersion, Instant loadedAt) {

    public ReleaseRef {
        Objects.requireNonNull(releaseKey);
        Objects.requireNonNull(methodVersion);
        Objects.requireNonNull(dataVersion);
    }
}

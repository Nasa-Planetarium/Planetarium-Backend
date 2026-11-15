package io.emberline.shared.release;

import java.time.Instant;
import java.util.Objects;

/**
 * The release a request is pinned to (chosen once at request start). Every release-table query binds
 * {@code release_id = :releaseId}, so one response never mixes two releases.
 *
 * @param releaseId partition value of every release table
 * @param releaseKey folder name under EMBERLINE_RELEASE_ROOT
 * @param methodVersion m2v_meta.json method_version (data version of the model, not the API version)
 * @param dataVersion identifier of the loaded file set ({@code <release_key>#<sha256(SHA256SUMS)[0:12]>})
 * @param loadedAt when the release finished loading
 */
public record ReleaseRef(long releaseId, String releaseKey, String methodVersion, String dataVersion, Instant loadedAt) {

    private static final String DATA_PREFIX = "data-";

    public ReleaseRef {
        Objects.requireNonNull(releaseKey);
        Objects.requireNonNull(methodVersion);
        Objects.requireNonNull(dataVersion);
    }

    /**
     * Contract {@code DataRelease} of the v1.2 layer answers: the release tag of the loaded folder, {@code data-} +
     * release_key unless the key already starts with it (folder {@code v1.2-2026-11-15} → {@code data-v1.2-2026-11-15}).
     * m2v_meta.json's own {@code data_release} still names v1.1 in the v1.2 release, so it is not used (no v1.1).
     */
    public String dataRelease() {
        return releaseKey.startsWith(DATA_PREFIX) ? releaseKey : DATA_PREFIX + releaseKey;
    }
}

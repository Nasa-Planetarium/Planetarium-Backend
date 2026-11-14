package io.emberline.ingest.domain;

import io.emberline.shared.error.ConflictException;
import io.emberline.shared.error.ErrorCode;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Aggregate: one release folder through its life. State changes only through the methods below, which enforce the
 * transition table; persistence uses the {@code version} column for optimistic locking.
 */
public final class Release {

    private final Long id;
    private final ReleaseKey key;
    private ReleaseState state;
    private String methodVersion;
    private String dataVersion;
    private String manifestSha256;
    private List<String> reasonCodes;
    private List<String> aliasesUsed;
    private List<String> warnings;
    private final long version;
    private final Instant stagedAt;
    private Instant loadedAt;
    private Instant activatedAt;

    public Release(Long id, ReleaseKey key, ReleaseState state, String methodVersion, String dataVersion,
                   String manifestSha256, List<String> reasonCodes, List<String> aliasesUsed, List<String> warnings,
                   long version, Instant stagedAt, Instant loadedAt, Instant activatedAt) {
        this.id = id;
        this.key = Objects.requireNonNull(key);
        this.state = Objects.requireNonNull(state);
        this.methodVersion = methodVersion;
        this.dataVersion = dataVersion;
        this.manifestSha256 = manifestSha256;
        this.reasonCodes = List.copyOf(reasonCodes);
        this.aliasesUsed = List.copyOf(aliasesUsed);
        this.warnings = List.copyOf(warnings);
        this.version = version;
        this.stagedAt = Objects.requireNonNull(stagedAt);
        this.loadedAt = loadedAt;
        this.activatedAt = activatedAt;
    }

    public static Release stage(ReleaseKey key, Instant now) {
        return new Release(null, key, ReleaseState.STAGED, null, null, null, List.of(), List.of(), List.of(), 0, now, null, null);
    }

    /** {@code <release_key>#<first 12 hex of sha256(SHA256SUMS)>} — stable for the same folder content. */
    public static String dataVersionOf(ReleaseKey key, String manifestSha256) {
        return key.value() + "#" + manifestSha256.substring(0, 12).toLowerCase(java.util.Locale.ROOT);
    }

    private void moveTo(ReleaseState next) {
        if (!state.canMoveTo(next)) {
            throw new ConflictException(ErrorCode.RELEASE_STATE_CONFLICT, "release_key",
                    "release " + key + " cannot go from " + state + " to " + next);
        }
        state = next;
    }

    /** A new attempt for a folder that was rejected or never finished validation. */
    public void restage() {
        moveTo(ReleaseState.STAGED);
        reasonCodes = List.of();
        warnings = List.of();
        aliasesUsed = List.of();
    }

    public void validated(String methodVersion, String manifestSha256, List<String> aliasesUsed, List<String> warnings) {
        moveTo(ReleaseState.VALIDATED);
        this.methodVersion = Objects.requireNonNull(methodVersion);
        this.manifestSha256 = Objects.requireNonNull(manifestSha256);
        this.dataVersion = dataVersionOf(key, manifestSha256);
        this.aliasesUsed = List.copyOf(aliasesUsed);
        this.warnings = List.copyOf(warnings);
    }

    public void startLoading() {
        moveTo(ReleaseState.LOADING);
    }

    public void loaded(Instant now) {
        moveTo(ReleaseState.LOADED);
        loadedAt = now;
    }

    public void activate(Instant now) {
        moveTo(ReleaseState.ACTIVE);
        activatedAt = now;
    }

    public void retire() {
        moveTo(ReleaseState.RETIRED);
    }

    public void reject(List<String> reasons) {
        moveTo(ReleaseState.REJECTED);
        reasonCodes = List.copyOf(reasons);
    }

    /** True when the folder's manifest hash and the separately supplied hash both equal the staged one. */
    public boolean manifestMatches(String expected, String current) {
        return manifestSha256 != null && manifestSha256.equalsIgnoreCase(expected) && manifestSha256.equalsIgnoreCase(current);
    }

    public Long id() {
        return id;
    }

    public ReleaseKey key() {
        return key;
    }

    public ReleaseState state() {
        return state;
    }

    public String methodVersion() {
        return methodVersion;
    }

    public String dataVersion() {
        return dataVersion;
    }

    public String manifestSha256() {
        return manifestSha256;
    }

    public List<String> reasonCodes() {
        return reasonCodes;
    }

    public List<String> aliasesUsed() {
        return aliasesUsed;
    }

    public List<String> warnings() {
        return warnings;
    }

    public long version() {
        return version;
    }

    public Instant stagedAt() {
        return stagedAt;
    }

    public Instant loadedAt() {
        return loadedAt;
    }

    public Instant activatedAt() {
        return activatedAt;
    }

    public Release withId(long newId) {
        return new Release(newId, key, state, methodVersion, dataVersion, manifestSha256, reasonCodes, aliasesUsed,
                warnings, version, stagedAt, loadedAt, activatedAt);
    }
}

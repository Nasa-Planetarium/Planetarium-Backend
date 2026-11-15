package io.emberline.harmonized.adapter.out.cache;

import io.emberline.shared.release.ReleaseRef;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Explicit redis-cache keys (no @Cacheable): {@code em:{env}:c:calendar:s{schema}:{method_version}:{data_version8}:{aoi}}.
 * data_version8 = the first 8 hex digits of the data_version hash ({@code <release_key>#<sha256[0:12]>}), so a
 * release switch or rollback reads a different key and nothing is ever deleted.
 */
public final class CacheKeys {

    /** Bump when the cached bundle changes shape (2: v1.2 r3 baseline_period, continuity_ratio, continuity_source). */
    public static final int BUNDLE_SCHEMA = 2;

    private CacheKeys() {
    }

    public static String calendarBundle(String env, ReleaseRef release, String aoiId) {
        return "em:" + env + ":c:calendar:s" + BUNDLE_SCHEMA + ":" + release.methodVersion() + ":"
                + dataVersion8(release.dataVersion()) + ":" + aoiId;
    }

    public static String dataVersion8(String dataVersion) {
        int hash = dataVersion.lastIndexOf('#');
        String hex = hash >= 0 ? dataVersion.substring(hash + 1) : sha256(dataVersion);
        return hex.length() >= 8 ? hex.substring(0, 8) : sha256(dataVersion).substring(0, 8);
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

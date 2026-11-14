package io.emberline.shared.release.load;

import java.nio.file.Path;
import java.util.Objects;

/**
 * What a loader sees of a release: the partition value, the folder (already resolved inside EMBERLINE_RELEASE_ROOT and
 * hash-checked), the method_version of m2v_meta.json and the shared validation report.
 */
public record ReleaseSource(long releaseId, String releaseKey, Path dir, String methodVersion, ValidationReport report) {

    public ReleaseSource {
        Objects.requireNonNull(releaseKey);
        Objects.requireNonNull(dir);
        Objects.requireNonNull(report);
    }

    public Path file(String name) {
        return dir.resolve(name);
    }

    public boolean has(String name) {
        return java.nio.file.Files.exists(file(name));
    }
}

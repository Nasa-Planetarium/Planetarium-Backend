package io.emberline.ingest.application.port.out;

import io.emberline.ingest.domain.Manifest;
import io.emberline.ingest.domain.ReleaseKey;
import java.nio.file.Path;
import java.util.Optional;

/** The release folder under EMBERLINE_RELEASE_ROOT: path resolution (no escape), manifest, file hashes, meta. */
public interface ReleaseFolderPort {

    /** {@code root/key}, normalized, inside the root; empty when it is not an existing directory. */
    Optional<Path> resolve(ReleaseKey key);

    /** Parsed SHA256SUMS; empty when absent or malformed. */
    Optional<Manifest> manifest(Path dir);

    String sha256(Path file);

    /** method_version of m2v_meta.json; empty when unreadable. */
    Optional<String> methodVersion(Path dir);
}

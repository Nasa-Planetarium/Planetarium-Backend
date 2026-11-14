package io.emberline.ingest.domain;

import java.util.Map;

/** The release folder's SHA256SUMS: file name → sha256, and the sha256 of the SHA256SUMS bytes themselves. */
public record Manifest(Map<String, String> files, String sha256) {

    public Manifest {
        files = Map.copyOf(files);
    }
}

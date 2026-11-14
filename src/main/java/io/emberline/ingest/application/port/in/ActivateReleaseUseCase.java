package io.emberline.ingest.application.port.in;

/** {@code ingest activate <release_key> --expect-sha256 <hex>}: switch the active release (also rollback). */
public interface ActivateReleaseUseCase {

    IngestOutcome activate(String releaseKey, String expectedManifestSha256);
}

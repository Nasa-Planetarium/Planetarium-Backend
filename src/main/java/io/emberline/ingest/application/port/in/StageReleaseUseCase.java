package io.emberline.ingest.application.port.in;

/** {@code ingest stage <release_key>}: validate and load a release folder (STAGED → … → LOADED, or REJECTED). */
public interface StageReleaseUseCase {

    IngestOutcome stage(String releaseKey);
}

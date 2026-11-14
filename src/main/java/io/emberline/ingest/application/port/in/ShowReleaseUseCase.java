package io.emberline.ingest.application.port.in;

/** {@code ingest show <release_key>}. */
public interface ShowReleaseUseCase {

    IngestOutcome show(String releaseKey);
}

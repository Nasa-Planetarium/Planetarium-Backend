package io.emberline.ingest.api;

/**
 * Published in the activation transaction (Modulith publication registry in schema platform). Pods also poll
 * ingest.active_release every ≤ 30 s, so a lost event only delays the switch.
 */
public record ReleaseActivated(long releaseId, String releaseKey, String methodVersion, String dataVersion,
                               Long previousReleaseId) {
}

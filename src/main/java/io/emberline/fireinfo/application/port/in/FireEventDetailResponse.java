package io.emberline.fireinfo.application.port.in;

/** GET /api/v1/fire-events/{eventId} (contract schema FireEventDetail). */
public record FireEventDetailResponse(FireEventItem event, String burnedAreaLabel, String dataRelease) {
}

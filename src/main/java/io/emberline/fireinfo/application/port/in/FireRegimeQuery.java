package io.emberline.fireinfo.application.port.in;

/** GET /api/v1/aois/{aoiId}/fire-regime: {@code start}, {@code end} as YYYY-MM (null = the whole file). */
public record FireRegimeQuery(String start, String end) {
}

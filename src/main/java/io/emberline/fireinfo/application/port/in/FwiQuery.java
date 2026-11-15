package io.emberline.fireinfo.application.port.in;

/** GET /api/v1/aois/{aoiId}/fwi: {@code date}, or {@code start} with {@code end} (YYYY-MM-DD; null = absent). */
public record FwiQuery(String date, String start, String end) {
}

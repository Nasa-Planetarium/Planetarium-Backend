package io.emberline.fireinfo.application.port.in;

/** GET /api/v1/fire-events as sent (formats checked by the request record, rules by the service; null = absent). */
public record FireEventListQuery(String aoiId, String bbox, String start, String end, Integer minDet, String sort,
                                 Integer limit, String cursor) {
}

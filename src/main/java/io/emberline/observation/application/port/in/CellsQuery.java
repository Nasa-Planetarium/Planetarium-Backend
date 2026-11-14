package io.emberline.observation.application.port.in;

/** GET /api/v1/cells after validation: exactly one of aoiId / bbox (raw "w,s,e,n"), month as YYYY-MM. */
public record CellsQuery(String aoiId, String bbox, String month) {
}

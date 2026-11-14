package io.emberline.catalog.application.port.in;

/** Normalized /aois query (validated by the request record). */
public record AoiListQuery(String level, String parentId, String q, boolean includeHidden, int limit, String cursor) {
}

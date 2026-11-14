package io.emberline.hotspots.domain;

/**
 * Where the next page starts: after every point ordered before {@code key}, then skipping {@code skip} points whose
 * key equals {@code key} (ties on the four public sort columns are ordered by the remaining columns, so the skip is
 * deterministic).
 */
public record PagePosition(SortKey key, int skip) {
}

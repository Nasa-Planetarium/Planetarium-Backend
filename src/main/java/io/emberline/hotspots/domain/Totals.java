package io.emberline.hotspots.domain;

/**
 * Over all points of a scan: {@code total} points of the layer, and the reference sum (is_reference points only).
 * {@code valueLo} / {@code valueHi} are null when any matching reference point has a null bound.
 */
public record Totals(long total, double value, Double valueLo, Double valueHi, long nPoints) {
}

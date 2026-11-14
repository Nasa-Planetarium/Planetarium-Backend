package io.emberline.hotspots.application.port.in;

/**
 * Σ over every matching is_reference point, independent of limit / cursor / layer. {@code valueLo} / {@code valueHi}
 * are null when a matching reference point has no bound. {@code note}: what the point sum leaves out.
 */
public record ReferenceSum(double value, Double valueLo, Double valueHi, long nPoints, String note) {
}

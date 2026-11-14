package io.emberline.hotspots.domain;

/** Extra selection on point coordinates after the bbox prefilter (AOI boundary contains). */
@FunctionalInterface
public interface PointFilter {

    boolean accepts(double latitude, double longitude);
}

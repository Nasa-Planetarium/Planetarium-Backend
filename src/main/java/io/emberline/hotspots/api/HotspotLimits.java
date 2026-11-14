package io.emberline.hotspots.api;

/**
 * The /hotspots span limits per layer (contract Version.limits hotspots_days_reference / hotspots_days_all). One source:
 * {@code emberline.hotspots.*}; the service enforces them and /version advertises them.
 */
public interface HotspotLimits {

    /** Longest start..end (days, inclusive) for {@code layer=reference}. */
    int maxDaysReference();

    /** Longest start..end (days, inclusive) for {@code layer=all}. */
    int maxDaysAll();
}

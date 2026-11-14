package io.emberline.provenance.application.port.in;

import io.emberline.shared.config.EmberlineProperties;

/**
 * /api/v1/version body. {@code release} is null before any activation. The top-level {@code method_version} and
 * {@code data_version} repeat the release block (contract schema Version) and are null without a release.
 */
public record VersionResponse(String apiVersion, String methodVersion, String dataVersion, ReleaseBlock release,
                              Limits limits) {

    public record ReleaseBlock(String releaseKey, String methodVersion, String dataVersion) {
    }

    /**
     * Contract Version.limits, keys in contract order: the /hotspots day spans as {@code emberline.hotspots.*} enforces
     * them, the rest from {@code emberline.limits.*}.
     */
    public record Limits(int hotspotsDaysReference, int hotspotsDaysAll, int hotspotsLimitMax, int summaryDayDays,
                         int summaryMonthYears, int calendarYears, int polygonVertices, int cellsMax, int csvRows) {

        public static Limits of(int hotspotsDaysReference, int hotspotsDaysAll, EmberlineProperties.Limits l) {
            return new Limits(hotspotsDaysReference, hotspotsDaysAll, l.hotspotsLimitMax(), l.summaryDayDays(),
                    l.summaryMonthYears(), l.calendarYears(), l.polygonVertices(), l.cellsMax(), l.csvRows());
        }
    }
}

package io.emberline.hotspots.application.port.in;

/**
 * One point as served (contract schema Hotspot): the 19 file columns in file order, timestamps as ISO-8601 text.
 * {@code country} / {@code adm1} are the contract names of the file columns {@code adm0_a3} / {@code adm1_code}
 * (values as stored: Natural Earth ISO3 and adm1_code, not an aoi_id).
 */
public record HotspotItem(double latitude, double longitude, String acqDatetimeUtc, String dateLocal, String satellite,
                          String instrument, Float frp, String confidence, String daynight, long cellId, String country,
                          String adm1, float harmonizedWeight, Float weightLo, Float weightHi, String weightSource,
                          boolean isReference, String source, String methodVersion) {
}

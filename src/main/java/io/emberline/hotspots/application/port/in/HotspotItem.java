package io.emberline.hotspots.application.port.in;

/** One point as served (contract schema Hotspot): the 19 file columns in file order, timestamps as ISO-8601 text. */
public record HotspotItem(double latitude, double longitude, String acqDatetimeUtc, String dateLocal, String satellite,
                          String instrument, Float frp, String confidence, String daynight, long cellId, String adm0A3,
                          String adm1Code, float harmonizedWeight, Float weightLo, Float weightHi, String weightSource,
                          boolean isReference, String source, String methodVersion) {
}

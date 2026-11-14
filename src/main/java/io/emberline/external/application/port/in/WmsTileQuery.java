package io.emberline.external.application.port.in;

/** GET /api/v1/firms/wms-tiles after validation (bbox raw "w,s,e,n"). */
public record WmsTileQuery(String layer, String bbox, int width, int height, String time) {
}

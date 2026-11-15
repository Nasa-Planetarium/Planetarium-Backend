package io.emberline.outlook.application.port.in;

/** Contract {@code GridOccurrenceCell}, the seven required fields ({@code fields=map}; numbers rounded to 4 decimals). */
public record GridCellMap(long cellId, double lat, double lon, double pAny, double baselineP, String vsNormal,
                          boolean persistentSource) implements GridCellView {
}

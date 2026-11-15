package io.emberline.outlook.application.port.in;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Contract {@code GridOccurrenceCell}, every field ({@code fields=full}; numbers rounded to 5 decimals for display). */
public record GridCellFull(long cellId, double lat, double lon, double pAny, Double pNone, double baselineP,
                           String vsNormal, boolean persistentSource, String adm0, String continent,
                           @JsonProperty("rate_5y") Double rate5y, Boolean lag1Projected) implements GridCellView {
}

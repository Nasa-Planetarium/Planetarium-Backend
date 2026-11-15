package io.emberline.fireinfo.domain;

/** One AOI-month of aoi_month_fire_regime.parquet, served columns as stored ({@code top_event_id} passed through). */
public record RegimeMonth(int year, int month, String fireRegime, Double w, Double wLarge, Double shareLarge,
                          Integer nEvents, Integer nLargeEvents, String topEventId, Double topEventW) {
}

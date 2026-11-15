package io.emberline.fireinfo.domain;

/** One candidate cell of spread_nowcast_latest.parquet, served columns as stored. */
public record SpreadCell(String region, String candAdm0, double cellLat, double cellLon, double cellSizeDeg,
                         double pSpread, boolean activeToday, Integer fireEventDays, Integer nDetToday7x7) {

    public SpreadLevel level() {
        return SpreadLevel.of(pSpread);
    }
}

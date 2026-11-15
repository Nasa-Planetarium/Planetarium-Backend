package io.emberline.fireinfo.domain;

import java.time.LocalDate;

/** One row of fire_events.parquet, the 26 served columns as stored (dates are local dates). */
public record FireEvent(String eventId, String region, LocalDate startDate, LocalDate endDate, int durationDays, int nDet,
                        double sumWeight, Double frpSum, Double frpMax, double centroidLat, double centroidLon,
                        double latMin, double latMax, double lonMin, double lonMax, Double burnedAreaKm2,
                        Boolean burnConfirmed, String instrument, Integer nViirs, Integer nModis, String country,
                        String adm1Code, String sidoNm, String sggCd, String sggNm, String sggAll) {
}

package io.emberline.fireinfo.application.port.in;

import io.emberline.fireinfo.domain.FireEvent;
import java.util.ArrayList;
import java.util.List;

/** Contract schema FireEvent: the 26 served columns of fire_events.parquet, names and values as stored. */
public record FireEventItem(String eventId, String region, String startDate, String endDate, int durationDays, int nDet,
                            double sumWeight, Double frpSum, Double frpMax, double centroidLat, double centroidLon,
                            double latMin, double latMax, double lonMin, double lonMax, Double burnedAreaKm2,
                            Boolean burnConfirmed, String instrument, Integer nViirs, Integer nModis, String country,
                            String adm1Code, String sidoNm, String sggCd, String sggNm, String sggAll) {

    /** Contract {@code x-csv-columns} of listFireEvents (same order as the fields). */
    public static final List<String> CSV_COLUMNS = List.of("event_id", "region", "start_date", "end_date", "duration_days",
            "n_det", "sum_weight", "frp_sum", "frp_max", "centroid_lat", "centroid_lon", "lat_min", "lat_max", "lon_min",
            "lon_max", "burned_area_km2", "burn_confirmed", "instrument", "n_viirs", "n_modis", "country", "adm1_code",
            "sido_nm", "sgg_cd", "sgg_nm", "sgg_all");

    public static FireEventItem of(FireEvent e) {
        return new FireEventItem(e.eventId(), e.region(), e.startDate().toString(), e.endDate().toString(), e.durationDays(),
                e.nDet(), e.sumWeight(), e.frpSum(), e.frpMax(), e.centroidLat(), e.centroidLon(), e.latMin(), e.latMax(),
                e.lonMin(), e.lonMax(), e.burnedAreaKm2(), e.burnConfirmed(), e.instrument(), e.nViirs(), e.nModis(),
                e.country(), e.adm1Code(), e.sidoNm(), e.sggCd(), e.sggNm(), e.sggAll());
    }

    public List<Object> csvRow() {
        List<Object> row = new ArrayList<>(CSV_COLUMNS.size());
        row.add(eventId);
        row.add(region);
        row.add(startDate);
        row.add(endDate);
        row.add(durationDays);
        row.add(nDet);
        row.add(sumWeight);
        row.add(frpSum);
        row.add(frpMax);
        row.add(centroidLat);
        row.add(centroidLon);
        row.add(latMin);
        row.add(latMax);
        row.add(lonMin);
        row.add(lonMax);
        row.add(burnedAreaKm2);
        row.add(burnConfirmed);
        row.add(instrument);
        row.add(nViirs);
        row.add(nModis);
        row.add(country);
        row.add(adm1Code);
        row.add(sidoNm);
        row.add(sggCd);
        row.add(sggNm);
        row.add(sggAll);
        return row;
    }
}

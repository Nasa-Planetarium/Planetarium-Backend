-- one page of fire events: period overlap (start_date <= end AND end_date >= start), n_det >= min_det, the area
-- condition (an area_*.sql fragment), the keyset condition (an after_*.sql fragment or TRUE) and the sort (an order_*.sql
-- fragment). The placeholders are filled by FireInfoJdbcRepository; they must not appear in this comment.
SELECT e.event_id, e.region, e.start_date, e.end_date, e.duration_days, e.n_det, e.sum_weight, e.frp_sum, e.frp_max,
       e.centroid_lat, e.centroid_lon, e.lat_min, e.lat_max, e.lon_min, e.lon_max, e.burned_area_km2, e.burn_confirmed,
       e.instrument, e.n_viirs, e.n_modis, e.country, e.adm1_code, e.sido_nm, e.sgg_cd, e.sgg_nm, e.sgg_all
FROM fireinfo.fire_event e
WHERE e.release_id = :releaseId AND e.start_date <= :end AND e.end_date >= :start AND e.n_det >= :minDet
  AND {area} AND {after}
ORDER BY {order}
LIMIT :limit

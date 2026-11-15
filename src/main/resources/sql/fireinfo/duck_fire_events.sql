-- DuckDB: fire_events.parquet, the 26 served columns (MAPPING §8.2) as stored; real numbers uncast so a FLOAT stays a FLOAT
SELECT CAST({event_id} AS VARCHAR), CAST({region} AS VARCHAR), CAST(CAST({start_date} AS DATE) AS VARCHAR),
       CAST(CAST({end_date} AS DATE) AS VARCHAR), CAST({duration_days} AS BIGINT), CAST({n_det} AS BIGINT),
       {sum_weight}, {frp_sum}, {frp_max}, {centroid_lat}, {centroid_lon}, {lat_min}, {lat_max}, {lon_min}, {lon_max},
       {burned_area_km2}, CAST({burn_confirmed} AS BOOLEAN), CAST({instrument} AS VARCHAR), CAST({n_viirs} AS BIGINT),
       CAST({n_modis} AS BIGINT), CAST({country} AS VARCHAR), CAST({adm1_code} AS VARCHAR), CAST({sido_nm} AS VARCHAR),
       CAST({sgg_cd} AS VARCHAR), CAST({sgg_nm} AS VARCHAR), CAST({sgg_all} AS VARCHAR)
FROM read_parquet(?)

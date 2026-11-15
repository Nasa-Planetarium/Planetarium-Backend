-- DuckDB: spread_nowcast_latest.parquet cells, served columns as stored (near_active_km, wind_align, weather_date not served)
SELECT CAST({region} AS VARCHAR), CAST({cand_adm0} AS VARCHAR), {cell_lat}, {cell_lon}, {cell_size_deg}, {p_spread},
       CAST({active_today} AS BOOLEAN), CAST({fire_event_days} AS BIGINT), CAST({n_det_today_7x7} AS BIGINT)
FROM read_parquet(?)

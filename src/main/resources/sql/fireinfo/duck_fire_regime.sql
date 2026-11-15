-- DuckDB: aoi_month_fire_regime.parquet, served columns as stored (level is not served)
SELECT CAST({aoi_id} AS VARCHAR), CAST({name} AS VARCHAR), CAST({year} AS BIGINT), CAST({month} AS BIGINT),
       CAST({fire_regime} AS VARCHAR), {W}, {W_large}, {share_large}, CAST({n_events} AS BIGINT),
       CAST({n_large_events} AS BIGINT), CAST({top_event_id} AS VARCHAR), {top_event_w}
FROM read_parquet(?)

-- DuckDB: fwi_clim_aoi_month.parquet as stored
SELECT CAST({aoi_id} AS VARCHAR), CAST({month} AS BIGINT), CAST({n_days} AS BIGINT), {q25}, {q50}, {q75}, {q90}
FROM read_parquet(?)

-- DuckDB: satellite_day_status.parquet (assumed columns G1; absent in v1-m2v-2026-11-14)
SELECT CAST({satellite} AS VARCHAR) AS satellite, CAST({date} AS DATE) AS date, CAST({status} AS VARCHAR) AS status,
       CAST({data_source} AS VARCHAR) AS data_source, CAST({updated_at} AS TIMESTAMPTZ) AS updated_at
FROM read_parquet(?)

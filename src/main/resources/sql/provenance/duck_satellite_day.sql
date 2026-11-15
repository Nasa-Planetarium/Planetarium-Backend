-- DuckDB: satellite_day_status.parquet (v1.2 real file: date_utc → date by alias, no data_source/updated_at, note kept)
SELECT CAST({satellite} AS VARCHAR) AS satellite, CAST({date} AS DATE) AS date, CAST({status} AS VARCHAR) AS status,
       CAST({data_source} AS VARCHAR) AS data_source, CAST({updated_at} AS TIMESTAMPTZ) AS updated_at,
       CAST({note} AS VARCHAR) AS note
FROM read_parquet(?)

-- DuckDB: satellite_day_status.parquet (v1.2 real file: date_utc → date by alias, no data_source/updated_at, note kept;
-- the served columns n_det_global, coverage, ratio_to_seasonal as the file has them)
SELECT CAST({satellite} AS VARCHAR) AS satellite, CAST({date} AS DATE) AS date, CAST({status} AS VARCHAR) AS status,
       CAST({data_source} AS VARCHAR) AS data_source, CAST({updated_at} AS TIMESTAMPTZ) AS updated_at,
       CAST({note} AS VARCHAR) AS note, CAST({n_det_global} AS BIGINT) AS n_det_global,
       CAST({coverage} AS VARCHAR) AS coverage, CAST({ratio_to_seasonal} AS DOUBLE) AS ratio_to_seasonal
FROM read_parquet(?)

-- DuckDB: satellite_month_status.parquet (v1.2), values as the file has them
SELECT CAST({satellite} AS VARCHAR) AS satellite, CAST({year} AS INTEGER) AS year, CAST({month} AS INTEGER) AS month,
       CAST({obs_days} AS BIGINT) AS obs_days, CAST({low_days} AS BIGINT) AS low_days,
       CAST({missing_days} AS BIGINT) AS missing_days, CAST(CAST({first_day} AS DATE) AS VARCHAR) AS first_day,
       CAST(CAST({last_day} AS DATE) AS VARCHAR) AS last_day, CAST({n_det_ea} AS BIGINT) AS n_det_ea,
       CAST({days_in_month} AS BIGINT) AS days_in_month, CAST({month_status} AS VARCHAR) AS month_status
FROM read_parquet(?)
ORDER BY 1, 2, 3

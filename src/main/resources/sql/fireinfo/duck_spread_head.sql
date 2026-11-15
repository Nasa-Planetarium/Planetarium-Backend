-- DuckDB: the distinct head of spread_nowcast_latest.parquet (one row expected: the file is one nowcast)
SELECT DISTINCT CAST(CAST({as_of_date} AS DATE) AS VARCHAR), CAST(CAST({target_date} AS DATE) AS VARCHAR),
       CAST({model} AS VARCHAR), CAST({source_satellite} AS VARCHAR), CAST({weather_available} AS BOOLEAN),
       CAST({weather_lag_days} AS BIGINT)
FROM read_parquet(?)

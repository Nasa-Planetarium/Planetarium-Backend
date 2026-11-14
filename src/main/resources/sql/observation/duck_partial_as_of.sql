-- DuckDB: as_of_date of each partial month in aoi_month.parquet (YYYYMM → date), for a cell file without as_of_date
SELECT CAST({year} AS INTEGER) * 100 + CAST({month} AS INTEGER) AS ym, CAST(max({as_of_date}) AS DATE) AS as_of_date
FROM read_parquet(?)
WHERE CAST({status} AS VARCHAR) = 'partial' AND {as_of_date} IS NOT NULL
GROUP BY 1

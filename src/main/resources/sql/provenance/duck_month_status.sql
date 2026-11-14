-- DuckDB: month status of the release = the WORLD rows of aoi_month.parquet, copied as-is (ruling 6: /satellite-status
-- answers with the file's month status while satellite_day_status is absent)
SELECT CAST({year} AS INTEGER) AS year, CAST({month} AS INTEGER) AS month, CAST({value_source} AS VARCHAR) AS value_source,
       CAST({data_source} AS VARCHAR) AS data_source, CAST({status} AS VARCHAR) AS status
FROM read_parquet(?)
WHERE {aoi_id} = ?
ORDER BY 1, 2

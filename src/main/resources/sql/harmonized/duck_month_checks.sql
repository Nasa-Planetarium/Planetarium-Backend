-- DuckDB: aoi_month rows with an impossible key
SELECT count(*) FILTER (WHERE {month} NOT BETWEEN 1 AND 12 OR {year} IS NULL OR {aoi_id} IS NULL OR {status} IS NULL)
FROM read_parquet(?)

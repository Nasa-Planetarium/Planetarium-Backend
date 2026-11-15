-- DuckDB: the events that name SGGs (sgg_all, comma-separated names as stored) with their sido_nm
SELECT CAST({event_id} AS VARCHAR), CAST({sido_nm} AS VARCHAR), CAST({sgg_all} AS VARCHAR)
FROM read_parquet(?)
WHERE {sgg_all} IS NOT NULL
ORDER BY 1

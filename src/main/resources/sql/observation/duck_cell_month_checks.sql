-- DuckDB: cell rows with an impossible key or coordinates
SELECT count(*) FILTER (WHERE {cell_id} IS NULL OR {year} IS NULL OR {month} NOT BETWEEN 1 AND 12 OR {status} IS NULL
                        OR {cell_lat} NOT BETWEEN -90 AND 90 OR {cell_lon} NOT BETWEEN -180 AND 180)
FROM read_parquet(?)

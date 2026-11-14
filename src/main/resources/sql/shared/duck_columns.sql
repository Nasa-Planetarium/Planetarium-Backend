-- DuckDB: columns and types of a parquet file (no rows read)
SELECT * FROM read_parquet(?) LIMIT 0

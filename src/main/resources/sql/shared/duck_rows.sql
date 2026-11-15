-- DuckDB: row count of a parquet file (from the footer, no rows read)
SELECT count(*) FROM read_parquet(?)

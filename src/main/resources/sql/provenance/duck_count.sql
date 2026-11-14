-- DuckDB: row count of a parquet file
SELECT count(*) FROM read_parquet(?)

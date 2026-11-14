-- DuckDB: distinct values of one column ({target} is a rendered column placeholder)
SELECT DISTINCT CAST({target} AS VARCHAR) AS v FROM read_parquet(?)

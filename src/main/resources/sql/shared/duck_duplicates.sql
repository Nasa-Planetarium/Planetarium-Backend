-- DuckDB: number of key values that occur more than once ({keys} = rendered key columns)
SELECT count(*) FROM (SELECT {keys} FROM read_parquet(?) GROUP BY ALL HAVING count(*) > 1)

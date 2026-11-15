-- DuckDB: FRP rows with an impossible key (null cell_id / date_local / n_points, cell_id off the 0.1° grid)
SELECT count(*) FILTER (WHERE {cell_id} IS NULL OR {date_local} IS NULL OR {n_points} IS NULL
                        OR {cell_id} < 0 OR {cell_id} >= 6480000)
FROM read_parquet(?)

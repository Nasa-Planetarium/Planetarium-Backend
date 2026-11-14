-- DuckDB: one centre per cell of harmonized_cell_month.parquet (cell_lat, cell_lon as the file has them)
SELECT CAST({cell_id} AS BIGINT) AS cell_id, CAST(min({cell_lat}) AS FLOAT) AS cell_lat, CAST(min({cell_lon}) AS FLOAT) AS cell_lon
FROM read_parquet(?)
GROUP BY {cell_id}

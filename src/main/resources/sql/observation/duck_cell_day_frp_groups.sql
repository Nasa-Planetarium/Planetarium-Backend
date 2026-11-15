-- DuckDB: rows and cell_id min/max per row group of harmonized_cell_day_frp.parquet (footer only) — the cut points of
-- the parallel COPY ranges
SELECT row_group_id, row_group_num_rows, stats_min_value, stats_max_value
FROM parquet_metadata(?)
WHERE path_in_schema = ?
ORDER BY row_group_id

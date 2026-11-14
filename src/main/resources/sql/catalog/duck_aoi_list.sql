-- DuckDB: aoi_list.parquet in hand-off names (placeholders = physical columns, aliases resolved)
SELECT CAST({aoi_id} AS VARCHAR) AS aoi_id, CAST({name_ko} AS VARCHAR) AS name_ko, CAST({name_en} AS VARCHAR) AS name_en,
       CAST({level} AS VARCHAR) AS level, CAST({parent_id} AS VARCHAR) AS parent_id, CAST({n_cells} AS BIGINT) AS n_cells,
       CAST({hidden} AS BOOLEAN) AS hidden
FROM read_parquet(?)
ORDER BY 1

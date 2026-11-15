-- DuckDB: the SGG and SIDO rows of aoi_list.parquet (aoi_id, name, parent) — the names sgg_all is resolved against
SELECT CAST({aoi_id} AS VARCHAR), CAST({name_ko} AS VARCHAR), CAST({parent_id} AS VARCHAR)
FROM read_parquet(?)
WHERE starts_with(CAST({aoi_id} AS VARCHAR), 'SGG_') OR starts_with(CAST({aoi_id} AS VARCHAR), 'SIDO_')
ORDER BY 1

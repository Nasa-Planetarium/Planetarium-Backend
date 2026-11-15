-- DuckDB: fwi_aoi_day.parquet, served columns as stored (level is not served)
SELECT CAST({aoi_id} AS VARCHAR), CAST(CAST({date} AS DATE) AS VARCHAR), CAST({name} AS VARCHAR), {fwi_mean}, {fwi_max},
       CAST({fwi_class} AS VARCHAR), {fwi_pct}, CAST({fwi_rel_class} AS VARCHAR), {isi_mean}, {bui_mean}, {ffmc_mean},
       {dc_mean}, CAST({n_cells} AS BIGINT)
FROM read_parquet(?)

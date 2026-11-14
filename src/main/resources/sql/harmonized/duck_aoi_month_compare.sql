-- DuckDB: aoi_month_compare.parquet in hand-off names (status and low_sample are optional columns)
SELECT CAST({aoi_id} AS VARCHAR) AS aoi_id, CAST({year} AS INTEGER) AS year, CAST({month} AS INTEGER) AS month,
       CAST({value} AS DOUBLE) AS value, CAST({baseline_median} AS DOUBLE) AS baseline_median,
       CAST({baseline_p25} AS DOUBLE) AS baseline_p25, CAST({baseline_p75} AS DOUBLE) AS baseline_p75,
       CAST({percentile} AS DOUBLE) AS percentile, CAST({ratio_to_median} AS DOUBLE) AS ratio_to_median,
       CAST({n_baseline_years} AS INTEGER) AS n_baseline_years, CAST({verdict} AS VARCHAR) AS verdict,
       CAST({status} AS VARCHAR) AS status, CAST({low_sample} AS BOOLEAN) AS low_sample
FROM read_parquet(?)

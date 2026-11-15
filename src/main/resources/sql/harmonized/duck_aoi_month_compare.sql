-- DuckDB: aoi_month_compare.parquet in hand-off names (status, low_sample, the v1.1 value_projected, as_of_date and the
-- v1.2 r3 baseline_period, continuity_ratio, continuity_source are optional columns)
SELECT CAST({aoi_id} AS VARCHAR) AS aoi_id, CAST({year} AS INTEGER) AS year, CAST({month} AS INTEGER) AS month,
       CAST({value} AS DOUBLE) AS value, CAST({baseline_median} AS DOUBLE) AS baseline_median,
       CAST({baseline_p25} AS DOUBLE) AS baseline_p25, CAST({baseline_p75} AS DOUBLE) AS baseline_p75,
       CAST({percentile} AS DOUBLE) AS percentile, CAST({ratio_to_median} AS DOUBLE) AS ratio_to_median,
       CAST({n_baseline_years} AS INTEGER) AS n_baseline_years, CAST({verdict} AS VARCHAR) AS verdict,
       CAST({status} AS VARCHAR) AS status, CAST({low_sample} AS BOOLEAN) AS low_sample,
       CAST({value_projected} AS DOUBLE) AS value_projected, CAST({as_of_date} AS DATE) AS as_of_date,
       CAST({baseline_period} AS VARCHAR) AS baseline_period, CAST({continuity_ratio} AS DOUBLE) AS continuity_ratio,
       CAST({continuity_source} AS VARCHAR) AS continuity_source
FROM read_parquet(?)

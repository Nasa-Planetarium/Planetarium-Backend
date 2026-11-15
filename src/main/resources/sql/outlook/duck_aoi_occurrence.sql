-- DuckDB: aoi_occurrence_nextmonth_latest.parquet, the served columns of MAPPING §8.2 as the file has them
-- (float32 widened to DOUBLE, which is exact; n_years float → integer, checked integral before load;
-- p_model, rate_all, lag1_value, occ_share, seasonal_cv are not read)
SELECT CAST({aoi_id} AS VARCHAR) AS aoi_id, CAST({target_month} AS VARCHAR) AS target_month,
       CAST({p_any} AS DOUBLE) AS p_any, CAST({p_none} AS DOUBLE) AS p_none, CAST({baseline_p} AS DOUBLE) AS baseline_p,
       CAST({model} AS VARCHAR) AS model, CAST(CAST({as_of_date} AS DATE) AS VARCHAR) AS as_of_date,
       CAST({suspected_industrial} AS BOOLEAN) AS suspected_industrial, CAST({rate_5y} AS DOUBLE) AS rate_5y,
       CAST({n_years} AS BIGINT) AS n_years, CAST({lag1_projected} AS BOOLEAN) AS lag1_projected
FROM read_parquet(?)
ORDER BY 1

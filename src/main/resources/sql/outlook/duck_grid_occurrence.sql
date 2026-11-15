-- DuckDB: grid_occurrence_nextmonth_latest.parquet, the served columns of MAPPING §8.2 as the file has them
-- (float32 widened to DOUBLE, which is exact; p_model is not read)
SELECT CAST({cell_id} AS BIGINT) AS cell_id, CAST({lat} AS DOUBLE) AS lat, CAST({lon} AS DOUBLE) AS lon,
       CAST({target_month} AS VARCHAR) AS target_month, CAST({p_any} AS DOUBLE) AS p_any,
       CAST({p_none} AS DOUBLE) AS p_none, CAST({baseline_p} AS DOUBLE) AS baseline_p,
       CAST({persistent_source} AS BOOLEAN) AS persistent_source, CAST({model} AS VARCHAR) AS model,
       CAST(CAST({as_of_date} AS DATE) AS VARCHAR) AS as_of_date, CAST({adm0} AS VARCHAR) AS adm0,
       CAST({continent} AS VARCHAR) AS continent, CAST({rate_5y} AS DOUBLE) AS rate_5y,
       CAST({lag1_projected} AS BOOLEAN) AS lag1_projected
FROM read_parquet(?)
ORDER BY 1

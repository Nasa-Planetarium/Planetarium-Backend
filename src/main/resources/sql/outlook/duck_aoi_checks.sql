-- DuckDB: aoi_occurrence rows the API cannot serve as stored (null in a served non-null column, NaN or ±inf),
-- the number of distinct head values (the list head has one target_month and as_of_date) and the n_years values
-- that are not integral (the API serves an integer)
SELECT count(*) FILTER (WHERE {aoi_id} IS NULL OR {target_month} IS NULL OR {p_any} IS NULL OR {model} IS NULL
                          OR {as_of_date} IS NULL OR {suspected_industrial} IS NULL) AS null_required,
       count(*) FILTER (WHERE NOT isfinite(CAST({p_any} AS DOUBLE)) OR NOT isfinite(CAST({p_none} AS DOUBLE))
                          OR NOT isfinite(CAST({baseline_p} AS DOUBLE)) OR NOT isfinite(CAST({rate_5y} AS DOUBLE))) AS not_finite,
       count(DISTINCT {target_month}) AS target_months,
       count(DISTINCT {as_of_date}) AS as_of_dates,
       count(*) FILTER (WHERE {n_years} IS NOT NULL AND (NOT isfinite(CAST({n_years} AS DOUBLE))
                          OR CAST({n_years} AS DOUBLE) <> trunc(CAST({n_years} AS DOUBLE))
                          OR abs(CAST({n_years} AS DOUBLE)) > 2147483647)) AS n_years_not_integral
FROM read_parquet(?)

-- DuckDB: grid_occurrence rows the API cannot serve as stored (null in a served non-null column, NaN or ±inf),
-- the number of distinct head values (the response head has one target_month, as_of_date and model) and the rows
-- whose cell_id breaks cell_id = floor((lat+90)/0.5)*720 + floor((lon+180)/0.5) (contract GridOccurrenceCell.cell_id)
SELECT count(*) FILTER (WHERE {cell_id} IS NULL OR {lat} IS NULL OR {lon} IS NULL OR {target_month} IS NULL
                          OR {p_any} IS NULL OR {baseline_p} IS NULL OR {persistent_source} IS NULL
                          OR {model} IS NULL OR {as_of_date} IS NULL) AS null_required,
       count(*) FILTER (WHERE NOT isfinite(CAST({lat} AS DOUBLE)) OR NOT isfinite(CAST({lon} AS DOUBLE))
                          OR NOT isfinite(CAST({p_any} AS DOUBLE)) OR NOT isfinite(CAST({p_none} AS DOUBLE))
                          OR NOT isfinite(CAST({baseline_p} AS DOUBLE)) OR NOT isfinite(CAST({rate_5y} AS DOUBLE))) AS not_finite,
       count(DISTINCT {target_month}) AS target_months,
       count(DISTINCT {as_of_date}) AS as_of_dates,
       count(DISTINCT {model}) AS models,
       count(*) FILTER (WHERE CAST({cell_id} AS BIGINT)
                        <> CAST(floor((CAST({lat} AS DOUBLE) + 90) / 0.5) * 720 + floor((CAST({lon} AS DOUBLE) + 180) / 0.5) AS BIGINT))
           AS cell_id_formula
FROM read_parquet(?)

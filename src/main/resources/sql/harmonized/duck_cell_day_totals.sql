-- DuckDB: Σ harmonized_cell_day per local date over the cell set. (cell_id, date_local) may repeat on day 1 (observed row +
-- residual carrier with value 0, n_points 0, NULL bounds), so everything is a SUM with NULLs skipped; residual_month is not
-- part of a day value. value_source = the observed rows' source.
SELECT CAST(d.date_local AS VARCHAR) AS date_local, sum(d.value) AS value, sum(d.value_lo) AS value_lo,
       sum(d.value_hi) AS value_hi, sum(d.n_points) AS n_points,
       min(d.value_source) FILTER (WHERE d.n_points > 0) AS value_source
FROM read_parquet(?) d
JOIN cells USING (cell_id)
WHERE d.date_local BETWEEN ? AND ?
GROUP BY d.date_local
ORDER BY d.date_local

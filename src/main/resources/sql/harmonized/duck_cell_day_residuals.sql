-- DuckDB: Σ residual_month per month over the cell set (stored on the day-1 rows only; a monthly amount, not a day-1 value),
-- and the part on background-prediction carrier rows (value_source modis_m2v_background, not in the residual file)
SELECT CAST(d.year AS INTEGER) AS year, CAST(d.month AS INTEGER) AS month, sum(d.residual_month) AS residual,
       coalesce(sum(d.residual_month) FILTER (WHERE d.value_source = 'modis_m2v_background'), 0) AS background
FROM read_parquet(?) d
JOIN cells USING (cell_id)
WHERE d.residual_month IS NOT NULL AND d.date_local BETWEEN ? AND ?
GROUP BY d.year, d.month
ORDER BY d.year, d.month

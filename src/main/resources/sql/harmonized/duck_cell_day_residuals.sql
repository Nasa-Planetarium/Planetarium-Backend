-- DuckDB: Σ residual_month per month over the cell set (stored on the day-1 rows only; a monthly amount, not a day-1 value)
SELECT CAST(d.year AS INTEGER) AS year, CAST(d.month AS INTEGER) AS month, sum(d.residual_month) AS residual
FROM read_parquet(?) d
JOIN cells USING (cell_id)
WHERE d.residual_month IS NOT NULL AND d.date_local BETWEEN ? AND ?
GROUP BY d.year, d.month
ORDER BY d.year, d.month

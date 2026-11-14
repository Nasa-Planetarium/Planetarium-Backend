-- DuckDB: Σ residual value per month and residual_type over the cell set (modis_unseen, missing_day_fill; negatives allowed)
SELECT CAST(r.year AS INTEGER) AS year, CAST(r.month AS INTEGER) AS month, r.residual_type, sum(r.value) AS residual
FROM read_parquet(?) r
JOIN cells USING (cell_id)
WHERE CAST(r.year AS INTEGER) * 100 + CAST(r.month AS INTEGER) BETWEEN ? AND ?
GROUP BY r.year, r.month, r.residual_type
ORDER BY r.year, r.month, r.residual_type

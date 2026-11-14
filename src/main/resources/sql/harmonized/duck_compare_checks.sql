-- DuckDB: aoi_month_compare rows outside the contract ranges (percentile 0–100, month 1–12)
SELECT count(*) FILTER (WHERE {percentile} < 0 OR {percentile} > 100 OR {month} NOT BETWEEN 1 AND 12 OR {year} IS NULL OR {aoi_id} IS NULL)
FROM read_parquet(?)

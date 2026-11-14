-- DuckDB: first and last month (YYYYMM) per aoi in aoi_month.parquet → has_data / first_month / last_month
SELECT CAST({aoi_id} AS VARCHAR) AS aoi_id, min(CAST({year} AS INTEGER) * 100 + CAST({month} AS INTEGER)) AS first_ym, max(CAST({year} AS INTEGER) * 100 + CAST({month} AS INTEGER)) AS last_ym
FROM read_parquet(?)
GROUP BY 1

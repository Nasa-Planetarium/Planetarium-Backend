-- DuckDB: rows per month (YYYYMM) — used to split the COPY into balanced parallel ranges
SELECT CAST({year} AS INTEGER) * 100 + CAST({month} AS INTEGER) AS ym, count(*) AS n FROM read_parquet(?) GROUP BY 1 ORDER BY 1

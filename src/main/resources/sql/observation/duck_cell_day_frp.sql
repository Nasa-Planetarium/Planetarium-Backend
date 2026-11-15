-- DuckDB: one cell_id range (lo, hi] of harmonized_cell_day_frp.parquet, the 6 columns as the file has them
-- (the file is cell_id-ordered, so the range lets DuckDB skip row groups by their min/max statistics)
SELECT CAST({cell_id} AS BIGINT) AS cell_id, CAST({frp_sum} AS DOUBLE) AS frp_sum,
       CAST({frp_snpp_equiv_sum} AS DOUBLE) AS frp_snpp_equiv_sum, CAST(CAST({date_local} AS DATE) AS VARCHAR) AS date_local,
       CAST({frp_snpp_equiv_max} AS FLOAT) AS frp_snpp_equiv_max, CAST({n_points} AS INTEGER) AS n_points
FROM read_parquet(?)
WHERE {cell_id} > ? AND {cell_id} <= ?

-- DuckDB (connection-local): the AOI cell set to join with
CREATE TEMP TABLE cells AS SELECT DISTINCT CAST(unnest(?) AS BIGINT) AS cell_id

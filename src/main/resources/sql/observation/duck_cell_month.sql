-- DuckDB: one month range of harmonized_cell_month.parquet in hand-off names, values as the file has them
SELECT CAST({cell_id} AS BIGINT) AS cell_id, CAST({value} AS DOUBLE) AS value, CAST({value_lo} AS DOUBLE) AS value_lo,
       CAST({value_hi} AS DOUBLE) AS value_hi, CAST({cell_lat} AS FLOAT) AS cell_lat, CAST({cell_lon} AS FLOAT) AS cell_lon,
       CAST({raw_modis} AS INTEGER) AS raw_modis, CAST({raw_viirs} AS INTEGER) AS raw_viirs,
       CAST({year} AS INTEGER) AS year, CAST({month} AS INTEGER) AS month,
       CAST({value_source} AS VARCHAR) AS value_source, CAST({data_source} AS VARCHAR) AS data_source,
       CAST({status} AS VARCHAR) AS status
FROM read_parquet(?)
WHERE CAST({year} AS INTEGER) * 100 + CAST({month} AS INTEGER) BETWEEN ? AND ?

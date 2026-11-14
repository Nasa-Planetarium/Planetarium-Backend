-- DuckDB: aoi_month.parquet in hand-off names, values as the file has them (value_projected, as_of_date: v1.1, optional)
SELECT CAST({aoi_id} AS VARCHAR) AS aoi_id, CAST({year} AS INTEGER) AS year, CAST({month} AS INTEGER) AS month,
       CAST({value} AS DOUBLE) AS value, CAST({value_lo} AS DOUBLE) AS value_lo, CAST({value_hi} AS DOUBLE) AS value_hi,
       CAST({raw_modis} AS BIGINT) AS raw_modis, CAST({raw_viirs} AS BIGINT) AS raw_viirs,
       CAST({value_source} AS VARCHAR) AS value_source, CAST({data_source} AS VARCHAR) AS data_source,
       CAST({status} AS VARCHAR) AS status, CAST({n_cells} AS BIGINT) AS n_cells,
       CAST({value_projected} AS DOUBLE) AS value_projected, CAST({as_of_date} AS DATE) AS as_of_date
FROM read_parquet(?)

-- DuckDB: rows per data case of harmonized_cell_month.parquet (column names = DataCase constants)
SELECT count(*) FILTER (WHERE {value} IS NULL AND {status} = 'missing') AS missing_month_null_value,
       count(*) FILTER (WHERE {value} IS NOT NULL AND {status} = 'missing') AS missing_month_with_value,
       count(*) FILTER (WHERE {value} IS NULL AND {status} IS DISTINCT FROM 'missing') AS null_value_not_missing,
       count(*) FILTER (WHERE {value} < {value_lo} OR {value} > {value_hi}) AS value_outside_interval,
       count(*) FILTER (WHERE {value_lo} > {value_hi}) AS interval_inverted,
       count(*) FILTER (WHERE {value} < 0 OR {value_lo} < 0 OR {value_hi} < 0 OR {raw_modis} < 0 OR {raw_viirs} < 0) AS negative_value
FROM read_parquet(?)

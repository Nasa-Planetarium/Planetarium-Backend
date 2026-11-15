-- DuckDB: rows per data case of aoi_month.parquet (column names = DataCase constants;
-- {interval_noise_tolerance} = DataCase.INTERVAL_NOISE_TOLERANCE, filled by FileChecks.dataCases)
SELECT count(*) FILTER (WHERE {value} IS NULL AND {status} = 'missing') AS missing_month_null_value,
       count(*) FILTER (WHERE {value} IS NOT NULL AND {status} = 'missing') AS missing_month_with_value,
       count(*) FILTER (WHERE {value} IS NULL AND {status} IS DISTINCT FROM 'missing') AS null_value_not_missing,
       count(*) FILTER (WHERE {value} < {value_lo} OR {value} > {value_hi}) AS value_outside_interval,
       count(*) FILTER (WHERE {value_lo} > {value_hi} AND NOT ({value_lo} - {value_hi} <= {interval_noise_tolerance})) AS interval_inverted,
       count(*) FILTER (WHERE {value_lo} > {value_hi} AND {value_lo} - {value_hi} <= {interval_noise_tolerance}) AS interval_inverted_noise,
       count(*) FILTER (WHERE {value} < 0 OR {value_lo} < 0 OR {value_hi} < 0) AS negative_value,
       count(*) FILTER (WHERE {raw_modis} < 0 OR {raw_viirs} < 0 OR {n_cells} < 0) AS negative_count
FROM read_parquet(?)

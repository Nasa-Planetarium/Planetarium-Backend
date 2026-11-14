-- Totals of a hotspot scan: every matching point, and the reference sum (is_reference only; a bound is null when
-- any matching reference point has a null bound). {files} = quoted parquet paths built by the adapter.
SELECT count(*) AS total,
       coalesce(sum(harmonized_weight::DOUBLE) FILTER (WHERE is_reference), 0) AS value,
       CASE WHEN count(*) FILTER (WHERE is_reference AND weight_lo IS NULL) > 0 THEN NULL
            ELSE coalesce(sum(weight_lo::DOUBLE) FILTER (WHERE is_reference), 0) END AS value_lo,
       CASE WHEN count(*) FILTER (WHERE is_reference AND weight_hi IS NULL) > 0 THEN NULL
            ELSE coalesce(sum(weight_hi::DOUBLE) FILTER (WHERE is_reference), 0) END AS value_hi,
       count(*) FILTER (WHERE is_reference) AS n_points
FROM read_parquet([{files}], hive_partitioning = false, union_by_name = true)
WHERE date_local BETWEEN ?::DATE AND ?::DATE
  AND latitude BETWEEN ? AND ? AND longitude BETWEEN ? AND ?

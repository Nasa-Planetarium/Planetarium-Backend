-- Coordinates and weights of every matching point (no order) — totals when a point filter (AOI boundary) applies.
SELECT latitude, longitude, is_reference, harmonized_weight::DOUBLE AS w, weight_lo::DOUBLE AS lo, weight_hi::DOUBLE AS hi
FROM read_parquet([{files}], hive_partitioning = false, union_by_name = true) p{static_antijoin}
WHERE date_local BETWEEN ?::DATE AND ?::DATE
  AND latitude BETWEEN ? AND ? AND longitude BETWEEN ? AND ?

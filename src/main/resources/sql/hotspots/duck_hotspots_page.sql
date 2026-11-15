-- One page of points in the public order (acq_datetime_utc, latitude, longitude, satellite); the remaining columns
-- break ties so equal keys come in a fixed order. The keyset bound is inclusive: the adapter skips the rows of the
-- boundary key that earlier pages already returned. With a point filter the adapter passes a limit it never reaches and stops reading itself.
SELECT latitude, longitude, epoch_us(acq_datetime_utc) AS acq_us, CAST(date_local AS VARCHAR) AS date_local,
       satellite, instrument, frp, confidence, daynight, cell_id, adm0_a3, adm1_code, harmonized_weight,
       weight_lo, weight_hi, weight_source, is_reference, source, method_version
FROM read_parquet([{files}], hive_partitioning = false, union_by_name = true) p{static_antijoin}
WHERE date_local BETWEEN ?::DATE AND ?::DATE
  AND latitude BETWEEN ? AND ? AND longitude BETWEEN ? AND ?
  AND (epoch_us(acq_datetime_utc), latitude, longitude, satellite) >= (?, ?, ?, ?)
ORDER BY acq_datetime_utc, latitude, longitude, satellite, instrument, is_reference, cell_id, frp, confidence,
         daynight, harmonized_weight, weight_lo, weight_hi, weight_source, source, adm0_a3, adm1_code, method_version,
         date_local
LIMIT ?

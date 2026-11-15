-- v1.2 static-source points: appended after the point scan alias p when the release has harmonized_hotspots_static_flags;
-- exact equality on the point key. {flag_files} = quoted year=YYYY.parquet paths built by the adapter.
 ANTI JOIN (SELECT satellite, latitude, longitude, acq_datetime_utc
            FROM read_parquet([{flag_files}], hive_partitioning = false, union_by_name = true)
            WHERE is_static_source) s
  ON p.satellite = s.satellite AND p.latitude = s.latitude AND p.longitude = s.longitude
 AND p.acq_datetime_utc = s.acq_datetime_utc
-- every month of one AOI with its comparison row (values as stored; one statement per cache miss)
SELECT m.year, m.month, m.value, m.value_lo, m.value_hi, m.value_projected, m.as_of_date, m.raw_modis, m.raw_viirs,
       m.value_source, m.data_source, m.status, m.n_cells,
       c.value AS compare_value, c.baseline_median, c.baseline_p25, c.baseline_p75, c.percentile, c.ratio_to_median,
       c.n_baseline_years, c.verdict, c.status AS compare_status, c.low_sample,
       c.value_projected AS compare_value_projected, c.as_of_date AS compare_as_of_date,
       c.baseline_period, c.continuity_ratio, c.continuity_source
FROM harmonized.aoi_month m
LEFT JOIN harmonized.aoi_month_compare c
       ON c.release_id = :releaseId AND c.aoi_id = m.aoi_id AND c.year = m.year AND c.month = m.month
WHERE m.release_id = :releaseId AND m.aoi_id = :aoiId
ORDER BY m.year, m.month

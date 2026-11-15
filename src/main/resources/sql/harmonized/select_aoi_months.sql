-- every month of one AOI with its comparison row (values as stored; one statement per cache miss). The band is served
-- ordered: a kept INTERVAL_INVERTED_NOISE row (lo > hi by <= 1e-4) comes out lower bound first; NULL bounds stay NULL.
SELECT m.year, m.month, m.value,
       CASE WHEN m.value_lo > m.value_hi THEN m.value_hi ELSE m.value_lo END AS value_lo,
       CASE WHEN m.value_lo > m.value_hi THEN m.value_lo ELSE m.value_hi END AS value_hi, m.value_projected, m.as_of_date, m.raw_modis, m.raw_viirs,
       m.value_source, m.data_source, m.status, m.n_cells,
       c.value AS compare_value, c.baseline_median, c.baseline_p25, c.baseline_p75, c.percentile, c.ratio_to_median,
       c.n_baseline_years, c.verdict, c.status AS compare_status, c.low_sample,
       c.value_projected AS compare_value_projected, c.as_of_date AS compare_as_of_date
FROM harmonized.aoi_month m
LEFT JOIN harmonized.aoi_month_compare c
       ON c.release_id = :releaseId AND c.aoi_id = m.aoi_id AND c.year = m.year AND c.month = m.month
WHERE m.release_id = :releaseId AND m.aoi_id = :aoiId
ORDER BY m.year, m.month

-- cells of one month whose center lies in the box (index (year, month, cell_lat, cell_lon) of the release partition).
-- The band is served ordered: a kept INTERVAL_INVERTED_NOISE row (lo > hi by <= 1e-4) comes out lower bound first.
SELECT cell_id, cell_lat, cell_lon, value,
       CASE WHEN value_lo > value_hi THEN value_hi ELSE value_lo END AS value_lo,
       CASE WHEN value_lo > value_hi THEN value_lo ELSE value_hi END AS value_hi, raw_modis, raw_viirs,
       value_source::text AS value_source, data_source::text AS data_source, status::text AS status,
       value_projected, as_of_date
FROM observation.cell_month
WHERE release_id = :releaseId
  AND year = :year AND month = :month
  AND cell_lat BETWEEN :south AND :north
  AND cell_lon BETWEEN :west AND :east
ORDER BY cell_id
LIMIT :limit

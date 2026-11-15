-- cells of one month whose center lies in the box (index (year, month, cell_lat, cell_lon) of the release partition)
SELECT cell_id, cell_lat, cell_lon, value, value_lo, value_hi, raw_modis, raw_viirs,
       value_source::text AS value_source, data_source::text AS data_source, status::text AS status,
       value_projected, as_of_date
FROM observation.cell_month
WHERE release_id = :releaseId
  AND year = :year AND month = :month
  AND cell_lat BETWEEN :south AND :north
  AND cell_lon BETWEEN :west AND :east
ORDER BY cell_id
LIMIT :limit

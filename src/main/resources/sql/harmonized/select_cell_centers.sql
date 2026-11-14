-- candidate cells of an AOI: centres inside the boundary's bbox (the exact contains test is JTS in the adapter)
SELECT cell_id, cell_lat, cell_lon
FROM harmonized.cell_center
WHERE release_id = :releaseId
  AND cell_lat BETWEEN :south AND :north
  AND cell_lon BETWEEN :west AND :east

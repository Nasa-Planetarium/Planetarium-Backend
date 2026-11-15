-- FRP rows of one release with date_local in [start, end] whose cell center lies in the grid rows/columns of the box
-- (row = cell_id / 3600, column = cell_id % 3600); the cell_id bounds narrow the (date_local, cell_id) index per day
SELECT cell_id, date_local, frp_sum, frp_snpp_equiv_sum, frp_snpp_equiv_max, n_points
FROM observation.cell_day_frp
WHERE release_id = :releaseId
  AND date_local BETWEEN :start AND :end
  AND cell_id BETWEEN :idLo AND :idHi
  AND cell_id / 3600 BETWEEN :rowLo AND :rowHi
  AND cell_id % 3600 BETWEEN :colLo AND :colHi
ORDER BY date_local, cell_id
LIMIT :limit

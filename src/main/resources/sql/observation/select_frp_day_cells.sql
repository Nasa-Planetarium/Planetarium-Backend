-- as select_frp_day.sql, restricted to a cell set (the AOI's cells by ruling R-SP1, an array literal {id,id,…})
SELECT cell_id, date_local, frp_sum, frp_snpp_equiv_sum, frp_snpp_equiv_max, n_points
FROM observation.cell_day_frp
WHERE release_id = :releaseId
  AND date_local BETWEEN :start AND :end
  AND cell_id BETWEEN :idLo AND :idHi
  AND cell_id / 3600 BETWEEN :rowLo AND :rowHi
  AND cell_id % 3600 BETWEEN :colLo AND :colHi
  AND cell_id = ANY (CAST(:cells AS bigint[]))
ORDER BY date_local, cell_id
LIMIT :limit

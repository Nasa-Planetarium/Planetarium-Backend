-- as select_frp_month.sql, restricted to a cell set (the AOI's cells by ruling R-SP1, an array literal {id,id,…})
SELECT cell_id, CAST(date_trunc('month', date_local) AS date) AS month_start,
       sum(frp_sum ORDER BY date_local) AS frp_sum, sum(frp_snpp_equiv_sum ORDER BY date_local) AS frp_snpp_equiv_sum,
       max(frp_snpp_equiv_max) AS frp_snpp_equiv_max, sum(n_points) AS n_points
FROM observation.cell_day_frp
WHERE release_id = :releaseId
  AND date_local BETWEEN :start AND :end
  AND cell_id BETWEEN :idLo AND :idHi
  AND cell_id / 3600 BETWEEN :rowLo AND :rowHi
  AND cell_id % 3600 BETWEEN :colLo AND :colHi
  AND cell_id = ANY (CAST(:cells AS bigint[]))
GROUP BY cell_id, month_start
ORDER BY month_start, cell_id
LIMIT :limit

-- cells whose centre lies in the box (bounds included), in file key order
SELECT region, cand_adm0, cell_lat, cell_lon, cell_size_deg, p_spread, active_today, fire_event_days, n_det_today_7x7
FROM fireinfo.spread_cell
WHERE release_id = :releaseId AND cell_lon BETWEEN :west AND :east AND cell_lat BETWEEN :south AND :north
ORDER BY region, cell_lat, cell_lon

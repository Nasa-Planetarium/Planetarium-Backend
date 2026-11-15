SELECT cell_id, lat, lon, target_month, p_any, p_none, baseline_p, persistent_source, model, as_of_date, adm0,
       continent, rate_5y, lag1_projected
FROM outlook.grid_occurrence
WHERE release_id = :releaseId
ORDER BY cell_id

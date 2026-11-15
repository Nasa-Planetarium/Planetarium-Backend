SELECT aoi_id, target_month, p_any, p_none, baseline_p, model, as_of_date, suspected_industrial, rate_5y, n_years,
       lag1_projected
FROM outlook.aoi_occurrence
WHERE release_id = :releaseId
ORDER BY aoi_id

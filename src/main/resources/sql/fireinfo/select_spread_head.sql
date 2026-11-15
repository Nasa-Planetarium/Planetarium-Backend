SELECT as_of_date, target_date, model, source_satellite, weather_available, weather_lag_days
FROM fireinfo.spread_head
WHERE release_id = :releaseId

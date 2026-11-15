SELECT satellite, year, month, obs_days, low_days, missing_days, days_in_month, first_day, last_day, month_status
FROM provenance.satellite_month
WHERE release_id = :releaseId
ORDER BY year, month, satellite

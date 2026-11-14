SELECT satellite, date, status, data_source, updated_at
FROM provenance.satellite_day
WHERE release_id = :releaseId AND date BETWEEN :start AND :end
ORDER BY date, satellite

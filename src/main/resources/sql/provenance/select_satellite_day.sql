SELECT satellite, date, status, data_source, updated_at, note, n_det_global, coverage, ratio_to_seasonal
FROM provenance.satellite_day
WHERE release_id = :releaseId AND date BETWEEN :start AND :end
ORDER BY date, satellite

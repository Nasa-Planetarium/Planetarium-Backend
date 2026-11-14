SELECT year, month, value_source, data_source, status
FROM provenance.month_status
WHERE release_id = :releaseId
ORDER BY year, month

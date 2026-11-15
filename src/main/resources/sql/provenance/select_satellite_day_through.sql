-- the last date_utc of satellite_day_status (null when the release did not ship the file)
SELECT max(date) FROM provenance.satellite_day WHERE release_id = :releaseId

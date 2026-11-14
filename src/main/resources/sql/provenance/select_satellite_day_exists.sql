SELECT EXISTS (SELECT 1 FROM provenance.satellite_day WHERE release_id = :releaseId)

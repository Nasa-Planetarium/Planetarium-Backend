SELECT method_version, meta::text AS meta, aliases_used::text AS aliases_used, warnings::text AS warnings, files::text AS files
FROM provenance.method_meta
WHERE release_id = :releaseId

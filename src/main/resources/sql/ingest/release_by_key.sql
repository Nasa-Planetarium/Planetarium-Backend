SELECT id, release_key, state, method_version, data_version, manifest_sha256, reason_codes::text AS reason_codes,
       aliases_used::text AS aliases_used, warnings::text AS warnings, version, staged_at, loaded_at, activated_at
FROM ingest.release
WHERE release_key = :releaseKey

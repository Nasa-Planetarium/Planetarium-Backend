SELECT r.id, r.release_key, r.method_version, r.data_version, r.loaded_at
FROM ingest.active_release a
JOIN ingest.release r ON r.id = a.release_id
WHERE a.id = 1

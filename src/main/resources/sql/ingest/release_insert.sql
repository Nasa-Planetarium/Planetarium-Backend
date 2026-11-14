INSERT INTO ingest.release (release_key, state, staged_at, updated_at)
VALUES (:releaseKey, :state, :stagedAt, :stagedAt)
RETURNING id

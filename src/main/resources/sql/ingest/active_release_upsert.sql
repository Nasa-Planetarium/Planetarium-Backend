INSERT INTO ingest.active_release (id, release_id, activated_at) VALUES (1, :releaseId, :at)
ON CONFLICT (id) DO UPDATE SET release_id = EXCLUDED.release_id, activated_at = EXCLUDED.activated_at

INSERT INTO ingest.load_task (release_id, context, state, started_at) VALUES (:releaseId, :context, 'LOADING', now())
ON CONFLICT (release_id, context) DO UPDATE SET state = 'LOADING', started_at = now(), finished_at = NULL, error_code = NULL,
    rows_loaded = '{}'::jsonb

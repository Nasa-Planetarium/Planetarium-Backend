UPDATE ingest.load_task SET state = :state, rows_loaded = CAST(:rows AS jsonb), error_code = :errorCode, finished_at = now()
WHERE release_id = :releaseId AND context = :context

-- session-level advisory lock held for the whole stage command (key = hashtext('emberline.ingest'))
SELECT pg_try_advisory_lock(hashtext('emberline.ingest'))

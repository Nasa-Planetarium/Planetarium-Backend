-- optimistic lock: 0 rows updated = someone changed the release meanwhile
UPDATE ingest.release
SET state = :state, method_version = :methodVersion, data_version = :dataVersion, manifest_sha256 = :manifestSha256,
    reason_codes = CAST(:reasonCodes AS jsonb), aliases_used = CAST(:aliasesUsed AS jsonb), warnings = CAST(:warnings AS jsonb),
    loaded_at = :loadedAt, activated_at = :activatedAt, updated_at = now(), version = version + 1
WHERE id = :id AND version = :version

-- whether the file carries the AOI (rows) and its name as the file has it
SELECT count(*) AS months, min(name) AS name
FROM fireinfo.fire_regime_month
WHERE release_id = :releaseId AND aoi_id = :aoiId

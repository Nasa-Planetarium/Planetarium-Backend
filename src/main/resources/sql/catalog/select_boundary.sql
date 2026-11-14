SELECT geometry::text AS geometry
FROM catalog.aoi_boundary
WHERE release_id = :releaseId AND aoi_id = :aoiId AND geometry IS NOT NULL

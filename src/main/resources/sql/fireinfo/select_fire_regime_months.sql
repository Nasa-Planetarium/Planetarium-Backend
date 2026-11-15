SELECT year, month, fire_regime, "W", "W_large", share_large, n_events, n_large_events, top_event_id, top_event_w
FROM fireinfo.fire_regime_month
WHERE release_id = :releaseId AND aoi_id = :aoiId AND year * 100 + month BETWEEN :from AND :to
ORDER BY year, month

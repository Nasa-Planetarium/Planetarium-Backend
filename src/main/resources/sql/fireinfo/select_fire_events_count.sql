-- every event of the selection (same conditions as select_fire_events_page.sql without the keyset)
SELECT count(*)
FROM fireinfo.fire_event e
WHERE e.release_id = :releaseId AND e.start_date <= :end AND e.end_date >= :start AND e.n_det >= :minDet
  AND {area}

EXISTS (SELECT 1 FROM fireinfo.fire_event_sgg s
        WHERE s.release_id = e.release_id AND s.sgg_id = :sggId AND s.event_id = e.event_id)

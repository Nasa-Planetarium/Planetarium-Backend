(e.burned_area_km2 < :afterValue OR (e.burned_area_km2 = :afterValue AND e.event_id COLLATE "C" > :afterId) OR e.burned_area_km2 IS NULL)

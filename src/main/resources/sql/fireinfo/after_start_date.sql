(e.start_date < :afterDate OR (e.start_date = :afterDate AND e.event_id COLLATE "C" > :afterId))

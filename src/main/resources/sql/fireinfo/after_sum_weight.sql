(e.sum_weight < :afterValue OR (e.sum_weight = :afterValue AND e.event_id COLLATE "C" > :afterId))

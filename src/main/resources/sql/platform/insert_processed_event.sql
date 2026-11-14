INSERT INTO platform.processed_event (consumer, event_id)
VALUES (:consumer, :eventId)
ON CONFLICT DO NOTHING

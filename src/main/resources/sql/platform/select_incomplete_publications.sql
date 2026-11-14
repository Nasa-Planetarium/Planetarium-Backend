-- incomplete publications of one listener and event type, oldest first (RedisStreamsExternalizer: eventId lookup)
SELECT id, publication_date, serialized_event
FROM platform.event_publication
WHERE listener_id = :listenerId
  AND event_type = :eventType
  AND completion_date IS NULL
ORDER BY publication_date, id

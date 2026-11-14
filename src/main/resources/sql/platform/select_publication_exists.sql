-- EnvelopeVerifier: an envelope is genuine only if its eventId is a stored publication (live or archived)
SELECT EXISTS (SELECT 1 FROM platform.event_publication WHERE id = :id)
    OR EXISTS (SELECT 1 FROM platform.event_publication_archive WHERE id = :id)

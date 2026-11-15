-- platform: consumer inbox. One row per (consumer, eventId) handled; the insert and the handler share a transaction.
CREATE TABLE IF NOT EXISTS processed_event
(
    consumer     TEXT                     NOT NULL,
    event_id     UUID                     NOT NULL,
    processed_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    PRIMARY KEY (consumer, event_id)
);

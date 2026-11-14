-- ingest: release registry (aggregate Release, optimistic lock on version), per-context load tasks, active pointer.
CREATE TABLE release (
    id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    release_key     text        NOT NULL UNIQUE CHECK (release_key ~ '^[a-z0-9][a-z0-9._-]{0,63}$'),
    state           text        NOT NULL CHECK (state IN ('STAGED', 'VALIDATED', 'LOADING', 'LOADED', 'ACTIVE', 'RETIRED', 'REJECTED')),
    method_version  text,
    data_version    text,
    manifest_sha256 text,
    manifest        jsonb       NOT NULL DEFAULT '{}'::jsonb,
    reason_codes    jsonb       NOT NULL DEFAULT '[]'::jsonb,
    aliases_used    jsonb       NOT NULL DEFAULT '[]'::jsonb,
    warnings        jsonb       NOT NULL DEFAULT '[]'::jsonb,
    version         bigint      NOT NULL DEFAULT 0,
    staged_at       timestamptz NOT NULL,
    loaded_at       timestamptz,
    activated_at    timestamptz,
    updated_at      timestamptz NOT NULL
);
-- at most one release LOADING and at most one ACTIVE at any time
CREATE UNIQUE INDEX release_one_loading ON release ((true)) WHERE state = 'LOADING';
CREATE UNIQUE INDEX release_one_active ON release ((true)) WHERE state = 'ACTIVE';

CREATE TABLE load_task (
    release_id  bigint      NOT NULL REFERENCES release (id),
    context     text        NOT NULL,
    state       text        NOT NULL CHECK (state IN ('LOADING', 'LOADED', 'FAILED')),
    rows_loaded jsonb       NOT NULL DEFAULT '{}'::jsonb,
    started_at  timestamptz NOT NULL,
    finished_at timestamptz,
    error_code  text,
    PRIMARY KEY (release_id, context)
);

CREATE TABLE active_release (
    id           smallint    PRIMARY KEY CHECK (id = 1),
    release_id   bigint      NOT NULL REFERENCES release (id),
    activated_at timestamptz NOT NULL
);

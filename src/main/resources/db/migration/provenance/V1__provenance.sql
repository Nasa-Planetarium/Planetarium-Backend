-- provenance: m2v_meta.json (json, as-is), month status of the release (WORLD rows of aoi_month.parquet, copied),
-- satellite_day_status.parquet when a release ships it (absent in v1-m2v-2026-11-14).
CREATE TABLE method_meta (
    release_id     bigint NOT NULL,
    method_version text   NOT NULL,
    meta           json   NOT NULL, -- json (not jsonb) keeps m2v_meta.json text and key order as-is
    aliases_used   jsonb  NOT NULL,
    warnings       jsonb  NOT NULL,
    files          jsonb  NOT NULL
) PARTITION BY LIST (release_id);

CREATE TABLE month_status (
    release_id   bigint   NOT NULL,
    year         smallint NOT NULL,
    month        smallint NOT NULL CHECK (month BETWEEN 1 AND 12),
    value_source text,
    data_source  text,
    status       text     NOT NULL
) PARTITION BY LIST (release_id);

CREATE TABLE satellite_day (
    release_id  bigint NOT NULL,
    satellite   text   NOT NULL,
    date        date   NOT NULL,
    status      text   NOT NULL,
    data_source text,
    updated_at  timestamptz
) PARTITION BY LIST (release_id);

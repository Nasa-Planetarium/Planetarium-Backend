-- v1.2: satellite_day_status.parquet carries a note (earlier_nrt_gap_refilled, partial_current_day, …); the release
-- also ships satellite_month_status.parquet (satellite × month). Unique (satellite, year, month) per release partition
-- (index_satellite_month.sql), like satellite_day (satellite, date).
ALTER TABLE satellite_day ADD COLUMN note text;

CREATE TABLE satellite_month (
    release_id    bigint   NOT NULL,
    satellite     text     NOT NULL,
    year          smallint NOT NULL,
    month         smallint NOT NULL CHECK (month BETWEEN 1 AND 12),
    obs_days      smallint,
    low_days      smallint,
    missing_days  smallint,
    first_day     date,
    last_day      date,
    n_det_ea      bigint,
    days_in_month smallint,
    month_status  text     NOT NULL
) PARTITION BY LIST (release_id);

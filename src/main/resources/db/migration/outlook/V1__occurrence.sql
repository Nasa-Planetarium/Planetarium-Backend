-- outlook: the model team's next-month occurrence files, copied as the release has them (MAPPING §8.2 served
-- columns; p_model and the AOI model features are not loaded). Probabilities are double precision holding the file's
-- float32 values widened exactly (no rounding at load; rounding is for display only). n_years is the file's float
-- converted to an integer, the loader refuses a value that is not integral. One unique key per release partition.
CREATE TABLE grid_occurrence (
    release_id        bigint           NOT NULL,
    cell_id           bigint           NOT NULL,
    lat               double precision NOT NULL,
    lon               double precision NOT NULL,
    target_month      text             NOT NULL,
    p_any             double precision NOT NULL,
    p_none            double precision,
    baseline_p        double precision NOT NULL,
    persistent_source boolean          NOT NULL,
    model             text             NOT NULL,
    as_of_date        date             NOT NULL,
    adm0              text,
    continent         text,
    rate_5y           double precision,
    lag1_projected    boolean
) PARTITION BY LIST (release_id);

CREATE TABLE aoi_occurrence (
    release_id           bigint           NOT NULL,
    aoi_id               text             NOT NULL,
    target_month         text             NOT NULL,
    p_any                double precision NOT NULL,
    p_none               double precision,
    baseline_p           double precision,
    model                text             NOT NULL,
    as_of_date           date             NOT NULL,
    suspected_industrial boolean          NOT NULL,
    rate_5y              double precision,
    n_years              integer,
    lag1_projected       boolean
) PARTITION BY LIST (release_id);

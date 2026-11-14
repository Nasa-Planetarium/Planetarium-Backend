-- observation: harmonized_cell_month.parquet (51.5M rows in v1-m2v-2026-11-14). Code columns are enums (4 bytes) instead
-- of text to keep the row near 100 bytes; method_version is not repeated per row (validated equal to m2v_meta at stage).
CREATE TYPE value_source AS ENUM ('modis_m2v_terra', 'modis_m2v', 'snpp_observed', 'n20_bridge', 'n21_bridge');
CREATE TYPE data_source AS ENUM ('SP', 'NRT');
CREATE TYPE month_status AS ENUM ('ok', 'nrt', 'partial', 'missing', 'low_sample');

CREATE TABLE cell_month (
    release_id   bigint           NOT NULL,
    cell_id      bigint           NOT NULL,
    value        double precision,
    value_lo     double precision,
    value_hi     double precision,
    cell_lat     real             NOT NULL,
    cell_lon     real             NOT NULL,
    raw_modis    integer,
    raw_viirs    integer,
    year         smallint         NOT NULL,
    month        smallint         NOT NULL CHECK (month BETWEEN 1 AND 12),
    value_source value_source,
    data_source  data_source,
    status       month_status     NOT NULL
) PARTITION BY LIST (release_id);

-- fireinfo: the v1.2 fire-info layers (contract MAPPING §8.2), one LIST partition per release. Only the served
-- columns are stored, named as in the release files. Real-valued columns are double precision holding the file value's
-- shortest decimal (a parquet FLOAT 0.92701 stays 0.92701 in JSON). The columns §8.2 does not serve are not stored:
-- fire_events burned_area_km2_near / pixel_area_*, spread near_active_km / wind_align / weather_date, fwi and regime level.

-- one row per layer file this release shipped (absent file → no row → 503 data_not_loaded 50502 on its routes)
CREATE TABLE layer (
    release_id bigint NOT NULL,
    file       text   NOT NULL,
    row_count  bigint NOT NULL
) PARTITION BY LIST (release_id);

-- fire_events.parquet
CREATE TABLE fire_event (
    release_id      bigint           NOT NULL,
    event_id        text             NOT NULL,
    region          text             NOT NULL,
    start_date      date             NOT NULL,
    end_date        date             NOT NULL,
    duration_days   integer          NOT NULL,
    n_det           integer          NOT NULL,
    sum_weight      double precision NOT NULL,
    frp_sum         double precision,
    frp_max         double precision,
    centroid_lat    double precision NOT NULL,
    centroid_lon    double precision NOT NULL,
    lat_min         double precision NOT NULL,
    lat_max         double precision NOT NULL,
    lon_min         double precision NOT NULL,
    lon_max         double precision NOT NULL,
    burned_area_km2 double precision,
    burn_confirmed  boolean,
    instrument      text,
    n_viirs         integer,
    n_modis         integer,
    country         text,
    adm1_code       text,
    sido_nm         text,
    sgg_cd          text,
    sgg_nm          text,
    sgg_all         text
) PARTITION BY LIST (release_id);

-- event → SGG AOI index built at load from sgg_all names resolved against aoi_list (MAPPING §8.4; sgg_cd not used)
CREATE TABLE fire_event_sgg (
    release_id bigint NOT NULL,
    event_id   text   NOT NULL,
    sgg_id     text   NOT NULL
) PARTITION BY LIST (release_id);

-- fwi_aoi_day.parquet
CREATE TABLE fwi_day (
    release_id    bigint           NOT NULL,
    aoi_id        text             NOT NULL,
    date          date             NOT NULL,
    name          text,
    fwi_mean      double precision,
    fwi_max       double precision,
    fwi_class     text,
    fwi_pct       double precision,
    fwi_rel_class text,
    isi_mean      double precision,
    bui_mean      double precision,
    ffmc_mean     double precision,
    dc_mean       double precision,
    n_cells       integer
) PARTITION BY LIST (release_id);

-- fwi_clim_aoi_month.parquet (the climatology fwi_rel_class was set from; FwiResponse names no field for it)
CREATE TABLE fwi_clim_month (
    release_id bigint           NOT NULL,
    aoi_id     text             NOT NULL,
    month      smallint         NOT NULL CHECK (month BETWEEN 1 AND 12),
    n_days     integer,
    q25        double precision,
    q50        double precision,
    q75        double precision,
    q90        double precision
) PARTITION BY LIST (release_id);

-- spread_nowcast_latest.parquet head columns (one value per file: as_of_date … weather_lag_days)
CREATE TABLE spread_head (
    release_id        bigint  NOT NULL,
    as_of_date        date,
    target_date       date,
    model             text,
    source_satellite  text,
    weather_available boolean NOT NULL,
    weather_lag_days  integer
) PARTITION BY LIST (release_id);

-- spread_nowcast_latest.parquet cells
CREATE TABLE spread_cell (
    release_id      bigint           NOT NULL,
    region          text             NOT NULL,
    cand_adm0       text,
    cell_lat        double precision NOT NULL,
    cell_lon        double precision NOT NULL,
    cell_size_deg   double precision NOT NULL,
    p_spread        double precision NOT NULL,
    active_today    boolean          NOT NULL,
    fire_event_days integer,
    n_det_today_7x7 integer
) PARTITION BY LIST (release_id);

-- aoi_month_fire_regime.parquet ("W" / "W_large" keep their upper-case file names)
CREATE TABLE fire_regime_month (
    release_id     bigint           NOT NULL,
    aoi_id         text             NOT NULL,
    name           text,
    year           smallint         NOT NULL,
    month          smallint         NOT NULL CHECK (month BETWEEN 1 AND 12),
    fire_regime    text             NOT NULL,
    "W"            double precision,
    "W_large"      double precision,
    share_large    double precision,
    n_events       integer,
    n_large_events integer,
    top_event_id   text,
    top_event_w    double precision
) PARTITION BY LIST (release_id);

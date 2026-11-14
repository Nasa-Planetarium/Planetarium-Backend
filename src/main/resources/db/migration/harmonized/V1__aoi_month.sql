-- harmonized: aoi_month.parquet and aoi_month_compare.parquet, values as the release has them (never recomputed).
-- status=missing keeps value NULL (never 0); a value outside [value_lo, value_hi] is kept (interval handling is the read side).
CREATE TABLE aoi_month (
    release_id   bigint           NOT NULL,
    aoi_id       text             NOT NULL,
    year         smallint         NOT NULL,
    month        smallint         NOT NULL CHECK (month BETWEEN 1 AND 12),
    value        double precision,
    value_lo     double precision,
    value_hi     double precision,
    raw_modis    bigint,
    raw_viirs    bigint,
    value_source text CHECK (value_source IN ('modis_m2v_terra', 'modis_m2v', 'snpp_observed', 'n20_bridge', 'n21_bridge')),
    data_source  text CHECK (data_source IN ('SP', 'NRT')),
    status       text             NOT NULL CHECK (status IN ('ok', 'nrt', 'partial', 'missing', 'low_sample')),
    n_cells      integer
) PARTITION BY LIST (release_id);

CREATE TABLE aoi_month_compare (
    release_id       bigint           NOT NULL,
    aoi_id           text             NOT NULL,
    year             smallint         NOT NULL,
    month            smallint         NOT NULL CHECK (month BETWEEN 1 AND 12),
    value            double precision,
    baseline_median  double precision,
    baseline_p25     double precision,
    baseline_p75     double precision,
    percentile       double precision,
    ratio_to_median  double precision,
    n_baseline_years smallint,
    verdict          text CHECK (verdict IN ('above', 'similar', 'below', 'uncertain')),
    status           text CHECK (status IN ('ok', 'nrt', 'partial', 'missing', 'low_sample')),
    low_sample       boolean
) PARTITION BY LIST (release_id);

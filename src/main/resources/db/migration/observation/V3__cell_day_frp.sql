-- data v1.2 (MAPPING §8.2): harmonized_cell_day_frp.parquet (97,791,096 rows in data-v1.2-2026-11-15), the 6 file
-- columns as stored — no latitude/longitude (the API derives the cell centre from cell_id). A stored 0 (or -0.0) is a
-- row: "value 0" is not "no row". Unique (date_local, cell_id) per release partition (index_cell_day_frp.sql), the key
-- MAPPING §8.4 asks for because the file is cell_id-ordered with every row group spanning all dates.
-- Column order keeps the 8-byte columns first (row ≈ 72 bytes with the tuple header).
CREATE TABLE cell_day_frp (
    release_id         bigint           NOT NULL,
    cell_id            bigint           NOT NULL,
    frp_sum            double precision,
    frp_snpp_equiv_sum double precision,
    date_local         date             NOT NULL,
    frp_snpp_equiv_max real,
    n_points           integer          NOT NULL
) PARTITION BY LIST (release_id);

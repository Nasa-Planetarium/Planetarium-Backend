-- (MAPPING §8.2): /satellite-status serves satellite_day_status's satellite · date_utc · status · n_det_global ·
-- coverage · ratio_to_seasonal · note. Nullable without a default (catalog-only change); partitions loaded before this
-- read NULL until the release is staged again.
ALTER TABLE satellite_day ADD COLUMN n_det_global bigint, ADD COLUMN coverage text, ADD COLUMN ratio_to_seasonal double precision;

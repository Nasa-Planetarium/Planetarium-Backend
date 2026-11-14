-- data v1.1 (00-decisions A4): the month in progress carries value_projected (full-month estimate) and as_of_date
-- (last observed date); complete months have value_projected = value and as_of_date = month end. Stored as the files have them.
ALTER TABLE aoi_month ADD COLUMN value_projected double precision, ADD COLUMN as_of_date date;
ALTER TABLE aoi_month_compare ADD COLUMN value_projected double precision, ADD COLUMN as_of_date date;

-- cell centres of harmonized_cell_month.parquet (cell_lat, cell_lon as float4, as in the file): the AOI cell set of
-- /summary is "cells whose centre lies inside the AOI boundary" (ruling R-SP1). harmonized keeps its own copy because
-- SQL may name only its own schema.
CREATE TABLE cell_center (
    release_id bigint NOT NULL,
    cell_id    bigint NOT NULL,
    cell_lat   real   NOT NULL,
    cell_lon   real   NOT NULL
) PARTITION BY LIST (release_id);

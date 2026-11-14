-- data v1.1 (contract §진행 중인 달): the month in progress carries value_projected (full-month estimate) and as_of_date
-- (last observed date). Nullable without a default, so adding them only changes the catalog (no table rewrite of the
-- 51.5M rows); existing partitions read NULL until the release is loaded again. Filled for status = partial rows only.
ALTER TABLE cell_month ADD COLUMN value_projected double precision, ADD COLUMN as_of_date date;

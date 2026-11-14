CREATE UNIQUE INDEX {child_name}_key ON {child} (cell_id);
CREATE INDEX {child_name}_latlon ON {child} (cell_lat, cell_lon)

CREATE UNIQUE INDEX {child_name}_key ON {child} (aoi_id, date);
CREATE INDEX {child_name}_date ON {child} (date)

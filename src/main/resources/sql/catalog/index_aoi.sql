CREATE UNIQUE INDEX {child_name}_key ON {child} (aoi_id);
CREATE INDEX {child_name}_parent ON {child} (parent_id)

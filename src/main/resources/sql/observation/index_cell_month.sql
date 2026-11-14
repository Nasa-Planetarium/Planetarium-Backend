-- key lookups and per-month bbox scans inside the release partition
CREATE UNIQUE INDEX {child_name}_key ON {child} (year, month, cell_id);
CREATE INDEX {child_name}_bbox ON {child} (year, month, cell_lat, cell_lon)

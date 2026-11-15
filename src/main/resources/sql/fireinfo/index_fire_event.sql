CREATE UNIQUE INDEX {child_name}_key ON {child} (event_id);
CREATE INDEX {child_name}_country ON {child} (country, start_date);
CREATE INDEX {child_name}_adm1 ON {child} (adm1_code, start_date);
CREATE INDEX {child_name}_sido ON {child} (sido_nm, start_date);
CREATE INDEX {child_name}_start ON {child} (start_date);
CREATE INDEX {child_name}_centroid ON {child} (centroid_lon, centroid_lat)

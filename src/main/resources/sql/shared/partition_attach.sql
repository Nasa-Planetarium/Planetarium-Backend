-- the CHECK lets ATTACH skip its validation scan; ANALYZE before the planner sees the partition
ALTER TABLE {child} ADD CONSTRAINT {child_name}_release CHECK (release_id = {release_id});
ANALYZE {child};
ALTER TABLE {parent} ATTACH PARTITION {child} FOR VALUES IN ({release_id})

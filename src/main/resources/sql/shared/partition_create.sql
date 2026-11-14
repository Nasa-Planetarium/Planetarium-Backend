-- child partition of one release (identifiers are built by the loader: {parent}, {child}, {release_id} is a long)
DROP TABLE IF EXISTS {child};
CREATE TABLE {child} (LIKE {parent} INCLUDING DEFAULTS INCLUDING CONSTRAINTS);
ALTER TABLE {child} ALTER COLUMN release_id SET DEFAULT {release_id}

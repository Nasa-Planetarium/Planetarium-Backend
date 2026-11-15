-- date range + box / cell set lookups inside the release partition (MAPPING §8.4); unique = the duplicate-key check
CREATE UNIQUE INDEX {child_name}_key ON {child} (date_local, cell_id)

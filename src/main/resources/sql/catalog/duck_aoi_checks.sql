-- DuckDB: aoi_list rows that cannot be served (null or malformed id, negative n_cells, null hidden), plus ids outside the
-- contract pattern ^[A-Za-z0-9_:.\-]{1,64}$ that the release really has (e.g. ADM1_ATA+00?) — kept, reported
SELECT count(*) FILTER (WHERE {aoi_id} IS NULL OR NOT regexp_full_match(CAST({aoi_id} AS VARCHAR), '[A-Za-z0-9_:.+?\-]{1,64}')) AS bad_id,
       count(*) FILTER (WHERE NOT regexp_full_match(CAST({aoi_id} AS VARCHAR), '[A-Za-z0-9_:.\-]{1,64}')) AS outside_contract_id,
       count(*) FILTER (WHERE {n_cells} IS NULL OR {n_cells} < 0) AS bad_n_cells,
       count(*) FILTER (WHERE {hidden} IS NULL) AS bad_hidden,
       count(*) FILTER (WHERE {parent_id} IS NOT NULL AND {parent_id} NOT IN (SELECT {aoi_id} FROM read_parquet(?))) AS orphan_parent
FROM read_parquet(?)

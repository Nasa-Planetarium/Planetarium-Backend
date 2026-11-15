-- Owner fixture core-v12cmp: a small, real-valued subset of release data-v1.2-2026-11-15 stage r3 (the replaced
-- aoi_month_compare.parquet with baseline_period / continuity_ratio / continuity_source). Values are
-- copied, never recomputed. AOIs: KR (2012-2025, own), WORLD and ADM0_ARG (2001-2025, own), SIDO_11 (2012-2025,
-- parent:KR) and SGG_31140 (2012-2025, parent:SIDO_31); every month of aoi_month.parquet and aoi_month_compare.parquet.
-- Run with any DuckDB client after replacing {src} (the r3 folder) and {out} (this fixture folder). Deterministic: one
-- thread, sorted output.
SET threads=1;
COPY (SELECT * FROM read_parquet('{src}/aoi_month.parquet')
      WHERE aoi_id IN ('KR', 'WORLD', 'ADM0_ARG', 'SIDO_11', 'SGG_31140') ORDER BY aoi_id, year, month)
  TO '{out}/aoi_month.parquet' (FORMAT parquet, COMPRESSION zstd);
COPY (SELECT * FROM read_parquet('{src}/aoi_month_compare.parquet')
      WHERE aoi_id IN ('KR', 'WORLD', 'ADM0_ARG', 'SIDO_11', 'SGG_31140') ORDER BY aoi_id, year, month)
  TO '{out}/aoi_month_compare.parquet' (FORMAT parquet, COMPRESSION zstd);

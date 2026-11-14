"""KR-bbox subset of harmonized_hotspots for the EMBERLINE /hotspots fixture.

Scope = cells of fixture-core-v11 (cell_month cell set); years 2004 (MODIS, lo/hi), 2011-2012
(MODIS->VIIRS switch at 2012-02), 2025 (March fire season), 2026 (partial 2026-11, N20 bridge).
Hive layout {reference,other}/year=YYYY/month=MM/part-0.parquet, byte-deterministic (threads=1, total order).
"""
import os, sys, duckdb
src, fix, out = sys.argv[1], sys.argv[2], sys.argv[3]
YEARS = (2004, 2011, 2012, 2025, 2026)
c = duckdb.connect(); c.execute("SET threads=1"); c.execute("SET TimeZone='UTC'")
c.execute(f"CREATE TEMP TABLE cells AS SELECT DISTINCT cell_id FROM read_parquet('{fix}/harmonized_cell_month.parquet')")
for layer in ("reference", "other"):
    for (y, m) in c.execute(f"""SELECT DISTINCT year, month FROM read_parquet('{src}/{layer}/*/*/*.parquet', hive_partitioning=true)
                              WHERE year IN {YEARS} ORDER BY 1, 2""").fetchall():
        y, m = int(y), int(m)
        sel = f"""SELECT h.* FROM read_parquet('{src}/{layer}/year={y}/month={m:02d}/*.parquet', hive_partitioning=false) h
                  JOIN cells USING (cell_id)
                  ORDER BY acq_datetime_utc, latitude, longitude, satellite, frp"""
        n = c.execute(f"SELECT count(*) FROM ({sel})").fetchone()[0]
        if n == 0:
            continue
        d = f"{out}/{layer}/year={y}/month={m:02d}"; os.makedirs(d, exist_ok=True)
        c.execute(f"COPY ({sel}) TO '{d}/part-0.parquet' (FORMAT parquet, COMPRESSION zstd)")
        print(layer, y, m, n, flush=True)
# cross-check: reference weights summed per cell x local date must equal fixture cell_day observed value
chk = c.execute(f"""WITH h AS (SELECT cell_id, date_local, sum(harmonized_weight) w, count(*) n
      FROM read_parquet('{out}/reference/*/*/*.parquet', hive_partitioning=true) GROUP BY ALL),
    d AS (SELECT cell_id, date_local, sum(value) v, sum(n_points) n FROM read_parquet('{fix}/harmonized_cell_day.parquet')
      WHERE year(date_local) IN {YEARS} GROUP BY ALL)
    SELECT count(*) FILTER (WHERE d.v IS NULL) only_h, count(*) FILTER (WHERE h.w IS NULL AND d.n > 0) only_d,
           count(*) FILTER (WHERE abs(h.w - d.v) > 1e-3) value_diff, count(*) FILTER (WHERE h.n <> d.n) n_diff, count(*) total
    FROM h FULL JOIN d USING (cell_id, date_local)""").fetchone()
print("crosscheck only_h,only_d,value_diff,n_diff,total =", chk, flush=True)

# harmonized_cell_day — repeated (cell_id, date_local) on day 1 (ruling R-CD1)

| why | what | how | opinion |
|---|---|---|---|
| on some day-1 dates a cell has an observed row and a separate residual-carrier row (value 0, n_points 0, NULL bounds, residual_month set); full release: 316,857 such keys, ≤ 2 rows per key (core-v11: 111, e.g. cell 4431110 on 2026-03-01). | any query over harmonized_cell_day. | every query SUMs value, value_lo, value_hi, n_points and residual_month per group (NULLs skipped); the day's `value_source` is the observed rows' (n_points > 0). residual_month is a monthly amount: never added to a day, added to whole months, pro rata to cut months. | never assume one row per key; the file is read in place with DuckDB, not loaded into PostgreSQL. |

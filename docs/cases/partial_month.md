# PARTIAL_MONTH — status `partial` (v1.1 month in progress)

| why | what | how | opinion |
|---|---|---|---|
| data v1.1 / team api_contract.md: the month in progress reports what was observed so far plus a projection. | a reading whose status is `partial` (core-v11: 2026-11, as_of_date 2026-11-13). | `value` = month-to-date as stored (KR 44.336265563964844), `value_projected` (KR 102.31446064148237) and `as_of_date` passed through; a verdict, where one is served, is `uncertain`; no residual is added; summary days after `as_of_date` are `out_of_range`. A `/summary` freq=month range from the 1st through `as_of_date` or later (e.g. 2026-11-01..2026-11-14) cuts no data day, so the month is the whole aoi_month row (basis `aoi_month`, no `partial_range`; R-SP3); only a range that leaves out a day ≤ `as_of_date` takes the cell_day path. | show both numbers ("2026-11-13까지 44.3, 이 추세면 약 102"); never compare a month-to-date value with full months. |

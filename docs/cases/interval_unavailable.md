# INTERVAL_UNAVAILABLE — value without an interval (Terra-only period)

| why | what | how | opinion |
|---|---|---|---|
| cell- and point-level intervals are null for 2000-11 – 2002-06 (no cell quantile model for Terra-only); the AOI interval exists only in aoi_month. | a reading with a value and a null `value_lo` or `value_hi` (summary day rows from harmonized_cell_day, partial-range months). | value kept; both bounds `null` (never filled with the value); `flags` gets `interval_unavailable`. | for an AOI interval in that period use the whole-month aoi_month row (calendar or freq=month). |

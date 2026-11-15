# INTERVAL_OUT_OF_RANGE — value outside [value_lo, value_hi]

| why | what | how | opinion |
|---|---|---|---|
| 「구간밖은 취급 안해야지. 예외 처리해야지」: the point model and the interval model are separate, so 441,269 cell rows (modis_m2v) have a value outside their own band. | a reading with value, value_lo and value_hi where value < value_lo or value > value_hi (relative tolerance 1e-9 against summation noise). | value, `value_lo` and `value_hi` served as stored (also in CSV); `flags` gets `interval_out_of_range`. | the row is not dropped; the front applies the display rule in one place (lo = min(lo, hi, value), hi = max(lo, hi, value), no band when lo is null); the API never clamps or nulls. AOI rows of core-v11 have no such case; it appears in summed cell_day readings. |

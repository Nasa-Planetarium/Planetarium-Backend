# MISSING_MONTH — status `missing`

| why | what | how | opinion |
|---|---|---|---|
| E2 「null 은 우리가 해결한다」: 2001-06 (Terra outage) has `status = missing` for every AOI; a 0 would read as "no fire". | a reading whose status is `missing`. | `value`, `value_lo`, `value_hi` → `null`, status stays `missing`; raw counts, percentile and verdict are the stored ones (verdict `uncertain` in the file). In comparison baselines the year is listed with `used: false`. | the UI paints grey, never 0; a missing month with a stored value (ingest case `MISSING_MONTH_WITH_VALUE`) is still served as missing. |

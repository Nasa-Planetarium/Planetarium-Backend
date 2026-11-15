# Data cases on the read side (harmonized)

One handler class per case, chosen by `io.emberline.harmonized.domain.cases.ReadCaseRegistry` (an `EnumMap`, no
`switch`). The registry applies every matching case in this order to each served reading
(calendar month, comparison, summary month or day). Ingest-side cases (reject/keep at load time) are
`io.emberline.shared.release.load.cases` and are listed in `docs/INGEST.md`.

| case | handler | note |
|---|---|---|
| `MISSING_MONTH` | `MissingMonthHandler` | [missing_month.md](missing_month.md) |
| `PARTIAL_MONTH` | `PartialMonthHandler` | [partial_month.md](partial_month.md) |
| `INTERVAL_OUT_OF_RANGE` | `IntervalOutOfRangeHandler` | [interval_out_of_range.md](interval_out_of_range.md) |
| `INTERVAL_UNAVAILABLE` | `IntervalUnavailableHandler` | [interval_unavailable.md](interval_unavailable.md) |
| `LOW_SAMPLE` | `LowSampleHandler` | [low_sample.md](low_sample.md) |

Not a handler (no row to handle): a month or day without a release row is served as `status: out_of_range` with
every value null. deviations and data findings: [deviations-read.md](deviations-read.md). The split day key of `harmonized_cell_day` is a query rule: [cell_day_split_key.md](cell_day_split_key.md).

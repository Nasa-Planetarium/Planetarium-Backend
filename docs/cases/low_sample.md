# LOW_SAMPLE — `low_sample = true`

| why | what | how | opinion |
|---|---|---|---|
| verdicts are the model team's; `low_sample` (baseline_p75 < 1) changes their rule (value ≥ 3 → above, else uncertain), not ours. | a reading whose stored `low_sample` is true. | the stored verdict and percentile are served unchanged — also `above` with a percentile under 80 (contract example ADM1_AZE-1699 2022-11). | never recomputed; the boolean `low_sample` is served next to the verdict so the UI can mark it "참고용". |

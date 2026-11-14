# `rate_limited`

problem `type`: `https://emberline.example/errors/rate_limited` · generated from `ErrorCode` Javadoc (same four cells) and `contract/openapi.yaml` `x-error-catalog`.

## 10301 `RATE_LIMITED` — HTTP 429

title: en “Too many requests” · ko “요청이 너무 많음”

| why | what | how | opinion |
|---|---|---|---|
| P1 scraper or over-eager refresh. | RateLimitFilter. | 429 with an integer `Retry-After`, written by ProblemWriter, counted per minute. | no event per request (log flood). |

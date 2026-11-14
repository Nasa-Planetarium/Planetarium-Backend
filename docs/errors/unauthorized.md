# `unauthorized`

problem `type`: `https://emberline.example/errors/unauthorized` · generated from `ErrorCode` Javadoc (same four cells) and `contract/openapi.yaml` `x-error-catalog`.

## 70301 `UNAUTHORIZED` — HTTP 401

title: en “Unauthorized” · ko “인증 필요”

| why | what | how | opinion |
|---|---|---|---|
| no or expired session. | AuthenticationEntryPoint. | 401 through ProblemWriter. | B5. |

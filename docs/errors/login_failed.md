# `login_failed`

problem `type`: `https://emberline.example/errors/login_failed` · generated from `ErrorCode` Javadoc (same four cells) and `contract/openapi.yaml` `x-error-catalog`.

## 70304 `LOGIN_FAILED` — HTTP 401

title: en “Login failed” · ko “로그인 실패”

| why | what | how | opinion |
|---|---|---|---|
| wrong password or unknown account. | LoginService. | 401 with a byte-identical body; unknown accounts also run a dummy Argon2id check. | exponential delay instead of account lock (P9). |

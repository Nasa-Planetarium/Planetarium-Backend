# `release_hash_mismatch`

problem `type`: `https://emberline.example/errors/release_hash_mismatch` · generated from `ErrorCode` Javadoc (same four cells) and `contract/openapi.yaml` `x-error-catalog`.

## 50002 `RELEASE_HASH_MISMATCH` — no HTTP · CLI exit 4

title: en “Release hash mismatch” · ko “release 해시 불일치”

| why | what | how | opinion |
|---|---|---|---|
| P8 tampered release. | sha256 of the release manifest differs from the value given on a separate channel. | CLI exit 4, the release becomes REJECTED, alert. | a hash inside the same folder cannot prove where the folder came from. |

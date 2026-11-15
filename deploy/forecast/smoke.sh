#!/bin/bash
# Offline smoke: the service image vs the model CLI on the same data and the same dates.
# One request per query, no load. The container runs with --network none, a read-only root and the release + state
# mounted read-only; requests go through `docker exec` to 127.0.0.1:8000 inside it.
# Usage: deploy/forecast/smoke.sh <model-dir> <out-dir>
#   <model-dir>  a scratch copy of the model repo (git archive c43368c) with its venv (uv sync --frozen --no-dev)
#   <out-dir>    where the responses, the CLI outputs and smoke.tsv go (its data link is composed here)
# Env: IMAGE (planetarium-forecast:c43368c), PL_RELEASE_ROOT ($HOME/planetarium/release), RELEASE_KEY
#      (v1.2-2026-11-15), STATE (forecast state dir, seeded with layout.py seed; default <out-dir>/state, seeded here).
# Dates: both sides get start = as_of + 1 from /healthz and horizon_days 30, so "today" (KST midnight) cannot split them.
set -euo pipefail
MODEL=$(cd "${1:?usage: smoke.sh <model-dir> <out-dir>}" && pwd)
OUT=${2:?usage: smoke.sh <model-dir> <out-dir>}
mkdir -p "$OUT" && OUT=$(cd "$OUT" && pwd)
HERE=$(cd "$(dirname "$0")" && pwd)
IMAGE=${IMAGE:-planetarium-forecast:c43368c}
RROOT=${PL_RELEASE_ROOT:-$HOME/planetarium/release}
KEY=${RELEASE_KEY:-v1.2-2026-11-15}
STATE=${STATE:-$OUT/state}
NAME=pl-forecast-smoke-$$
RUN=(docker run --rm --network none --read-only --tmpfs /tmp:size=64m --cap-drop ALL --security-opt no-new-privileges
     --user "$(id -u):$(id -g)" -v "$RROOT:/release:ro")

if [ ! -L "$STATE/current" ]; then
  mkdir -p "$STATE"
  "${RUN[@]}" -v "$STATE:/state" --entrypoint python "$IMAGE" /opt/forecast/layout.py seed --release "/release/$KEY" --state /state
fi

docker run -d --name "$NAME" "${RUN[@]:2}" -v "$STATE:/state:ro" -e FORECAST_RELEASE_DIR="/release/$KEY" \
  -e FORECAST_STATE_DIR=/state "$IMAGE" >/dev/null
trap 'docker logs "$NAME" > "$OUT/server.log" 2>&1 || true; docker rm -f "$NAME" >/dev/null 2>&1 || true' EXIT

# http <method> <path> [<body file>] → prints "<status>\n<body>" (client inside the container, stdlib only)
http() {
  local body=""
  [ -n "${3:-}" ] && body=$(cat "$3")
  docker exec -i "$NAME" python -c '
import sys, urllib.request, urllib.error
m, p, b = sys.argv[1], sys.argv[2], sys.stdin.buffer.read()
req = urllib.request.Request("http://127.0.0.1:8000" + p, data=b if m == "POST" else None, method=m,
                             headers={"Content-Type": "application/json"})
try:
    r = urllib.request.urlopen(req, timeout=30); code, out = r.status, r.read()
except urllib.error.HTTPError as e:
    code, out = e.code, e.read()
sys.stdout.write(str(code) + "\n"); sys.stdout.flush(); sys.stdout.buffer.write(out)' "$1" "$2" <<< "$body"
}

for i in $(seq 1 120); do
  h=$(http GET /healthz 2>/dev/null || true)
  [ "$(head -1 <<< "$h")" = 200 ] && break
  sleep 0.5
done
[ "$(head -1 <<< "$h")" = 200 ] || { echo "smoke: /healthz never became 200: $h" >&2; exit 1; }
tail -n +2 <<< "$h" > "$OUT/healthz.json"
AS_OF=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["as_of"])' "$OUT/healthz.json")
START=$(date -u -d "$AS_OF + 1 day" +%F)
echo "healthz $(cat "$OUT/healthz.json"); start=$START"

# The CLI reads the same release + the same published as_of dir through a view composed by the same layout.py.
python3 "$HERE/layout.py" compose --release "$RROOT/$KEY" --state "$STATE" --data-link "$OUT/pl-data"
ln -sfn "$OUT/pl-data" "$MODEL/data"

POLY='{"type": "Polygon", "coordinates": [[[126.5, 37.0], [127.5, 37.0], [127.5, 38.0], [126.5, 37.0]]]}'
cli() { (cd "$MODEL" && uv run --frozen --no-sync python -m planetarium.forecast --region "$1" --horizon-days 30 --start "$START"); }
# The CLI parses --region as an aoi_id or "w,s,e,n" only; a polygon goes through the same function with main()'s
# json.dumps arguments.
cli_poly() { (cd "$MODEL" && uv run --frozen --no-sync python -c 'import json, sys
from planetarium.forecast import forecast
print(json.dumps(forecast(json.loads(sys.argv[1]), sys.argv[2], None, None, horizon_days=30), ensure_ascii=False, indent=1, default=str))' "$POLY" "$START"); }

QUERIES=(SIDO_51 SGG_47730 ADM1_ARG-1299 bbox polygon)
declare -A BODY=(
  [SIDO_51]="{\"region\": \"SIDO_51\", \"horizon_days\": 30, \"start\": \"$START\"}"
  [SGG_47730]="{\"region\": \"SGG_47730\", \"horizon_days\": 30, \"start\": \"$START\"}"
  [ADM1_ARG-1299]="{\"region\": \"ADM1_ARG-1299\", \"horizon_days\": 30, \"start\": \"$START\"}"
  [bbox]="{\"region\": [126.5, 37.0, 127.5, 38.0], \"horizon_days\": 30, \"start\": \"$START\"}"
  [polygon]="{\"region\": $POLY, \"horizon_days\": 30, \"start\": \"$START\"}"
)
for q in "${QUERIES[@]}"; do
  printf '%s' "${BODY[$q]}" > "$OUT/req_$q.json"
  r=$(http POST /forecast "$OUT/req_$q.json")
  head -1 <<< "$r" > "$OUT/svc_$q.status"
  tail -n +2 <<< "$r" > "$OUT/svc_$q.json"
  case $q in
    bbox) cli 126.5,37.0,127.5,38.0 > "$OUT/cli_$q.json" ;;
    polygon) cli_poly > "$OUT/cli_$q.json" ;;
    *) cli "$q" > "$OUT/cli_$q.json" ;;
  esac
done
docker stats --no-stream --format 'container memory after the smoke: {{.MemUsage}}' "$NAME"

python3 - "$OUT" "${QUERIES[@]}" <<'EOF'
import json, sys
out, qs = sys.argv[1], sys.argv[2:]
want = {"SIDO_51": ("skill", "high"), "SGG_47730": ("value_source", "baseline"), "ADM1_ARG-1299": ("display_hint", "non-empty")}
rows, ok_all = [], True
for q in qs:
    status = open(f"{out}/svc_{q}.status").read().strip()
    svc_raw = open(f"{out}/svc_{q}.json", "rb").read().rstrip(b"\n")
    cli_raw = open(f"{out}/cli_{q}.json", "rb").read().rstrip(b"\n")
    svc, cli = json.loads(svc_raw), json.loads(cli_raw)
    cli.pop("elapsed_s", None)         # the CLI adds its own timing; forecast() does not return it
    byte_eq = svc_raw == cli_raw
    ordered = json.dumps(svc, ensure_ascii=False) == json.dumps(cli, ensure_ascii=False)
    sorted_eq = json.dumps(svc, ensure_ascii=False, sort_keys=True) == json.dumps(cli, ensure_ascii=False, sort_keys=True)
    f, v = want.get(q, (None, None))
    check = "-" if f is None else (bool(svc.get(f)) if v == "non-empty" else svc.get(f) == v)
    ok = status == "200" and sorted_eq and check is not False
    ok_all &= ok
    rows.append([q, status, "yes" if byte_eq else "no", "yes" if ordered else "no", "yes" if sorted_eq else "NO",
                 svc["region"], svc["start"], svc["end"], svc["as_of"], svc["p_any"], svc["skill"], svc["value_source"],
                 svc["expected_count"], repr(svc["display_hint"]), len(svc["daily"]), f"{f}={check}" if f else "-", "PASS" if ok else "FAIL"])
hdr = ["query", "http", "byte_equal", "equal_key_order", "equal_sorted", "region", "start", "end", "as_of", "p_any", "skill",
       "value_source", "expected_count", "display_hint", "daily", "check", "result"]
with open(f"{out}/smoke.tsv", "w", encoding="utf-8") as fh:
    for r in [hdr] + rows:
        fh.write("\t".join(map(str, r)) + "\n")
for r in [hdr] + rows:
    print("\t".join(map(str, r)))
print("smoke:", "PASS" if ok_all else "FAIL", f"({sum(r[-1] == 'PASS' for r in rows)}/{len(rows)})")
sys.exit(0 if ok_all else 1)
EOF

#!/bin/bash
# Owner deploy: build the image from a worktree that already ran `./gradlew --offline bootJar`, import it into k3s,
# apply the app manifest. Usage: deploy/deploy.sh <worktree> [--load <release_key>]
# Host values (the LAN origin) come from the untracked env file read by deploy/render.sh.
# Afterwards pl-web learns the active data_version (its proxy_cache key): see sync_data_version below.
set -euo pipefail
W=$1; shift
LOAD=""
[ "${1:-}" = "--load" ] && LOAD=$2
SHA=$(git -C "$W" rev-parse --short=8 HEAD)
IMG=planetarium-backend:$SHA
SLUG=$(echo "${LOAD:-none}" | tr '.' '-')-$SHA
# The cluster's kubeconfig: PL_KUBECONFIG from the untracked env file (see deploy/render.sh), else KUBECONFIG.
env_file=${PL_DEPLOY_ENV:-$HOME/planetarium/deploy.env}
# shellcheck source=/dev/null
[ -r "$env_file" ] && . "$env_file"
export KUBECONFIG=${PL_KUBECONFIG:-${KUBECONFIG:?set PL_KUBECONFIG in the env file or KUBECONFIG}}
NS=planetarium
docker build -q -f "$W/deploy/docker/Dockerfile" -t "$IMG" "$W"
docker save "$IMG" | sudo -n k3s ctr images import - >/dev/null
render() { sed -e "s#__IMAGE__#$IMG#g" -e "s#__RELEASE_KEY__#${LOAD:-none}#g" -e "s#__RELEASE_SLUG__#$SLUG#g" "$W/deploy/k8s/planetarium-app.yaml" | "$W/deploy/render.sh" -; }
if [ -n "$LOAD" ]; then
  render | kubectl apply -f - 2>&1 | grep -v -i warning
else
  render | python3 -c 'import sys; docs=sys.stdin.read().split("\n---\n"); print("\n---\n".join(d for d in docs if "kind: Job" not in d))' | kubectl apply -f - 2>&1 | grep -v -i warning
fi
echo "image=$IMG load=${LOAD:-none}"

# The pl-web proxy_cache key starts with the active data_version, read from ConfigMap pl-web-release
# (data-version.map). When Spring serves another data_version than nginx keys on, rewrite the map and restart pl-web
# (rolling, maxUnavailable 0): the new pod starts with an empty cache under the new key, so no response of the old
# release is served from cache after the switch. Until then nginx stores no response whose X-Data-Version differs from
# its key (map $emberline_skip_store). Usage: sync_data_version [<release_key to wait for>]
sync_data_version() {
  local want=${1:-} key=- dv=none body i current
  kubectl -n "$NS" rollout status deployment/pl-app --timeout=600s >/dev/null
  for i in $(seq 1 60); do
    body=$(kubectl -n "$NS" exec deploy/pl-web -c nginx -- wget -qO- http://pl-app:8080/api/v1/version 2>/dev/null || true)
    read -r key dv <<<"$(printf '%s' "$body" | python3 -c 'import json,sys
try:
    v = json.load(sys.stdin)
except ValueError:
    sys.exit(1)
r = v.get("release") or {}
print(r.get("release_key") or "-", v.get("data_version") or "none")')"
    # An empty or non-JSON reply (pl-app endpoints not switched yet) leaves key empty: retry, never record "none".
    if [ -n "$key" ] && { [ -z "$want" ] || [ "$key" = "$want" ]; }; then break; fi
    sleep 5
  done
  if [ -z "$key" ]; then
    echo "pl-app /api/v1/version gave no JSON reply; pl-web cache key left unchanged" >&2
    return 1
  fi
  if [ -n "$want" ] && [ "$key" != "$want" ]; then
    echo "pl-app does not serve release $want yet (serves $key); pl-web cache key left unchanged" >&2
    return 1
  fi
  if ! [[ "$dv" =~ ^[A-Za-z0-9._:#+-]{1,96}$ ]]; then
    echo "unexpected data_version from /api/v1/version; pl-web cache key left unchanged" >&2
    return 1
  fi
  current=$(kubectl -n "$NS" get configmap pl-web-release -o jsonpath='{.data.data-version\.map}' 2>/dev/null || true)
  local map="map \"\" \$emberline_data_version { default \"$dv\"; }"
  if [ "$(echo "$current" | tr -d '\n')" = "$map" ]; then
    echo "pl-web data_version=$dv (unchanged)"
    return 0
  fi
  kubectl -n "$NS" create configmap pl-web-release --from-literal=data-version.map="$map" --dry-run=client -o yaml | kubectl apply -f - >/dev/null
  kubectl -n "$NS" rollout restart deployment/pl-web >/dev/null
  kubectl -n "$NS" rollout status deployment/pl-web --timeout=300s >/dev/null
  echo "pl-web data_version=$dv (cache key switched, pl-web restarted)"
}

# Waits for the load Job by polling its conditions: 0 on Complete, 1 at once on Failed (a rejected stage exits 3 and the
# Job fails; a kubectl wait on the complete condition never returns on that and hung until its timeout), 1 after the
# ceiling. Usage: wait_load_job <job> <ceiling seconds>
wait_load_job() {
  local job=$1 ceiling=$2 start=$SECONDS conds
  while :; do
    conds=$(kubectl -n "$NS" get "job/$job" -o jsonpath='{range .status.conditions[?(@.status=="True")]}{.type}{"\n"}{end}' 2>/dev/null || true)
    if grep -qx 'Complete' <<<"$conds"; then
      return 0
    fi
    if grep -qxE 'Failed|FailureTarget' <<<"$conds"; then
      echo "job/$job failed; see: kubectl -n $NS logs job/$job" >&2
      return 1
    fi
    if [ $((SECONDS - start)) -ge "$ceiling" ]; then
      echo "job/$job neither Complete nor Failed after ${ceiling}s" >&2
      return 1
    fi
    sleep "${LOAD_POLL_SECONDS:-10}"
  done
}

if [ -n "$LOAD" ]; then
  wait_load_job "pl-load-$SLUG" 3600
  sync_data_version "$LOAD"
else
  sync_data_version
fi

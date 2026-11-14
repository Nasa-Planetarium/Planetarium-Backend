#!/bin/bash
# Owner deploy: build the image from a worktree that already ran `./gradlew --offline bootJar`, import it into k3s,
# apply the app manifest. Usage: deploy/deploy.sh <worktree> [--load <release_key>]
set -euo pipefail
W=$1; shift
LOAD=""
[ "${1:-}" = "--load" ] && LOAD=$2
SHA=$(git -C "$W" rev-parse --short=8 HEAD)
IMG=planetarium-backend:$SHA
export KUBECONFIG=$HOME/.kube/omb-k3s.yaml
docker build -q -f "$W/deploy/docker/Dockerfile" -t "$IMG" "$W"
docker save "$IMG" | sudo -n k3s ctr images import - >/dev/null
render() { sed -e "s#__IMAGE__#$IMG#g" -e "s#__RELEASE_KEY__#${LOAD:-none}#g" -e "s#__RELEASE_SLUG__#$(echo "${LOAD:-none}" | tr '.' '-')-$SHA#g" "$W/deploy/k8s/planetarium-app.yaml"; }
if [ -n "$LOAD" ]; then
  render | kubectl apply -f - 2>&1 | grep -v -i warning
else
  render | python3 -c 'import sys; docs=sys.stdin.read().split("\n---\n"); print("\n---\n".join(d for d in docs if "kind: Job" not in d))' | kubectl apply -f - 2>&1 | grep -v -i warning
fi
echo "image=$IMG load=${LOAD:-none}"

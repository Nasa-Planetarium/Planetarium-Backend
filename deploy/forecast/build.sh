#!/bin/bash
# Build the FireOutlook Planner service image from the model repo at the pinned commit into the local docker.
# Usage: deploy/forecast/build.sh <model-commit> [<model-repo-dir>]
# <model-commit> must resolve to the pinned commit below (model repo main c43368c)
# <model-repo-dir> a checkout that has the commit; default $PL_MODEL_REPO. Only read (git archive), never changed.
# Tags planetarium-forecast:<commit7> (the tag deploy/k8s/planetarium-forecast.yaml runs) and
# planetarium-forecast:<commit7>-<this repo HEAD sha8> (to roll back to).
# The k3s import is the owner's step (deploy/forecast/README.md):
# docker save planetarium-forecast:c43368c | sudo -n k3s ctr images import -
set -euo pipefail
PINNED=c43368c62bd8253879c0547527bdb9441b36aa1a
COMMIT=${1:?usage: build.sh <model-commit> [<model-repo-dir>]}
REPO=${2:-${PL_MODEL_REPO:?set PL_MODEL_REPO or pass the model repo dir}}
FULL=$(git -C "$REPO" rev-parse --verify --quiet "$COMMIT^{commit}") || { echo "build: $COMMIT is not a commit of $REPO" >&2; exit 2; }
[ "$FULL" = "$PINNED" ] || { echo "build: $COMMIT is $FULL, the pinned model commit is $PINNED" >&2; exit 2; }
HERE=$(cd "$(dirname "$0")" && pwd)
SELF=$(git -C "$HERE" rev-parse --short=8 HEAD)
TAG=planetarium-forecast:${FULL:0:7}
CTX=$(mktemp -d)
trap 'rm -rf "$CTX"' EXIT
git -C "$REPO" archive --format=tar -o "$CTX/model.tar" "$FULL"
cp "$HERE/Dockerfile" "$HERE/server.py" "$HERE/layout.py" "$HERE/refresh.py" "$CTX/"
docker build ${DOCKER_BUILD_ARGS:-} --build-arg MODEL_COMMIT="$FULL" -t "$TAG" -t "$TAG-$SELF" "$CTX"
docker run --rm --network none --read-only --user 10001 --entrypoint python "$TAG" -c \
  'from planetarium.config import PROCESSED_DATA_DIR; import planetarium.forecast, sys; assert str(PROCESSED_DATA_DIR) == "/opt/model/data/processed", PROCESSED_DATA_DIR; print("image ok", sys.version.split()[0], PROCESSED_DATA_DIR)'
echo "image=$TAG ($TAG-$SELF) model=$FULL"

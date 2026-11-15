#!/bin/bash
# Fill a manifest's host placeholders and print it, so host values never enter the repository. __HOST_HOME__ becomes
# $HOME; __LAN_ORIGIN__ becomes PL_LAN_ORIGIN (the LAN browser origin, http://<HOST_IP>:<LAN_PORT>) from the untracked
# env file ${PL_DEPLOY_ENV:-$HOME/planetarium/deploy.env}. Refuses when the value is unset or a placeholder is left.
# Usage: deploy/render.sh <manifest|-> | kubectl apply -f - (deploy.sh pipes planetarium-app.yaml through it)
set -euo pipefail
env_file=${PL_DEPLOY_ENV:-$HOME/planetarium/deploy.env}
[ -r "$env_file" ] || { echo "render: $env_file is missing (it sets PL_LAN_ORIGIN)" >&2; exit 2; }
# shellcheck source=/dev/null
. "$env_file"
: "${PL_LAN_ORIGIN:?render: PL_LAN_ORIGIN is unset in the env file}"
out=$(sed -e "s#__HOST_HOME__#$HOME#g" -e "s#__LAN_ORIGIN__#$PL_LAN_ORIGIN#g" "${1:--}")
if grep -q -E '__(HOST_HOME|LAN_ORIGIN)__' <<< "$out"; then echo "render: a placeholder is left" >&2; exit 2; fi
printf '%s\n' "$out"

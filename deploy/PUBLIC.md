# EMBERLINE public exposure — owner steps (batch pl-pub)

Target: about 200 concurrent reviewers (CMD-ddcff409522b, CMD-dd93c8af56c0).

```
browser ─▶ Cloudflare Pages emberline.pages.dev (static, frontend repo GitHub Actions, Direct Upload)
             └─ Pages Function /api/v1/* (GET/HEAD) ─ service binding API ─▶ Worker emberline-api (no public URL)
                  └─ vpc_services binding ORIGIN ─▶ Workers VPC Service ─▶ Cloudflare Tunnel "emberline"
                       └─ cloudflared Deployment emberline-tunnel (K3s, ns planetarium, 2 replicas)
                            └─ pl-web-tunnel.planetarium.svc.cluster.local:8090 (nginx tunnel listener, allowlist)
                                 └─ pl-app:8080 (Spring)
LAN: 192.168.0.10:18490 ─ pl-web-forward-18490.service (kubectl port-forward) ─▶ pl-web :8080 (static www + /api, as before)
```

Nothing in this repository holds a token, an account ID or a secret. Tokens are typed into the shell or the dashboard and
end up in K8s Secret `emberline-tunnel` or GitHub secrets only. Every `kubectl` below uses
`KUBECONFIG=$HOME/.kube/omb-k3s.yaml` and namespace `planetarium`.

## A. pl-web listeners (LAN entry unchanged)

1. K3s ServiceLB is disabled on aibox, so the LAN entry stays `pl-web-forward-18490.service` (kubectl port-forward to
   Service `pl-web:8080`). LAN callers reach nginx as 127.0.0.1 and share one rate-limit address (DEVIATIONS PUB-LAN).
2. Apply: `kubectl apply -f deploy/k8s/planetarium.yaml` (new pl-web config with two listeners, ConfigMap
   `pl-web-release`, Service `pl-web-tunnel`, NetworkPolicy `pl-web-ingress`). Then
   `kubectl -n planetarium rollout status deploy/pl-web`.
3. Write the current `data_version` into nginx: `deploy/deploy.sh <worktree of the running image>` (no `--load`;
   rebuilds the same image tag, then syncs `pl-web-release`). Until this runs, nginx keys on `none` and caches nothing.
4. Verify from another LAN machine: `curl -si http://192.168.0.10:18490/api/v1/version | head -20` → 200 with
   `X-Cache:` and `Cache-Control: public, max-age=0, s-maxage=5, must-revalidate`; the site loads in a browser.
   The forward reconnects by itself after the pl-web restart (its loop retries every 3 s).

## B. Rate limits and cache headers

Apply the app with the new numbers: `deploy/deploy.sh <worktree at this branch>` (pl-app-env now carries the
`EMBERLINE_RATELIMIT_*` and `EMBERLINE_FIRMS_BUDGETPERCALLER` values; arithmetic in docs/DEVIATIONS.md PUB-RL).
After every later activation (`--load`) deploy.sh waits for the Job, waits until `/api/v1/version` serves the new
release, rewrites the `data-version.map` and restarts pl-web. After a manual `ingest activate` or rollback, run
`deploy/deploy.sh <worktree>` once to resync.

## C. Cloudflare (in this order)

### 1. Account API token (owner)

Dashboard → My Profile → API Tokens → Create Custom Token, account `<your account>` only:

| Permission | Why |
|---|---|
| Account · Cloudflare Pages · Edit | create project `emberline`, Direct Upload |
| Account · Workers Scripts · Edit | deploy Worker `emberline-api` |
| Account · Cloudflare Tunnel · Edit | create tunnel `emberline` |
| Account · Connectivity Directory · Admin | create the Workers VPC Service |

Keep it in the owner's shell only (`read -rs CLOUDFLARE_API_TOKEN; export CLOUDFLARE_API_TOKEN`). CI gets its own,
narrower token in step 6.

### 2. Tunnel

1. Zero Trust → Networks → Tunnels → Create a tunnel → Cloudflared → name `emberline`. Add **no** public hostname
   (the VPC Service routes to it; nothing is published on a hostname).
2. Copy the connector token from the install command and store it without echoing it:
   `read -rs T; kubectl -n planetarium create secret generic emberline-tunnel --from-literal=token="$T"; unset T`.
3. `kubectl apply -f deploy/k8s/planetarium-tunnel.yaml`, then
   `kubectl -n planetarium rollout status deploy/emberline-tunnel`. The dashboard shows the tunnel HEALTHY with 2
   connectors. These pods are separate from the flexday and agtt connectors.

### 3. Workers VPC Service

Workers & Pages → VPC → Create VPC Service (or `wrangler vpc service create` with the installed wrangler's flags):
type HTTP, tunnel `emberline`, host `pl-web-tunnel.planetarium.svc.cluster.local`, HTTP port `8090`. cloudflared
resolves that name with the cluster DNS. Copy the service id into `deploy/cloudflare/worker/wrangler.toml`
(`service_id`; it is an identifier, not a credential — keep the committed file as the placeholder if you prefer and
edit only the deploy copy).

### 4. Worker `emberline-api`

`cd deploy/cloudflare/worker && node --test test/*.test.js && npx wrangler deploy`. Check in the dashboard that the
Worker has no workers.dev URL, no preview URLs and no routes.

### 5. Pages project `emberline` (Direct Upload)

1. `npx wrangler pages project create emberline --production-branch main`.
2. In the frontend repo: copy `deploy/cloudflare/pages-function-sample/functions/api/v1/[[path]].js` to
   `functions/api/v1/[[path]].js` and merge `deploy/cloudflare/pages-function-sample/wrangler.toml` (service binding
   `API` → `emberline-api`). The frontend calls the API same-origin at `/api/v1/...`.
3. If the binding is set in the dashboard instead: Pages → emberline → Settings → Bindings → Service binding `API` →
   `emberline-api`, for Production and Preview.

### 6. GitHub secrets (repo admin of the frontend repo)

Settings → Secrets and variables → Actions:
- `CLOUDFLARE_API_TOKEN` — a separate token with **Account · Cloudflare Pages · Edit** only.
- `CLOUDFLARE_ACCOUNT_ID` — the account id.

The workflow runs `wrangler pages deploy <build dir> --project-name emberline` with these two.

### 7. Verify

- `node tools/public-smoke.mjs https://emberline.pages.dev --pages` → every line PASS, last line `SMOKE OK`.
- Listener-level, from the node: `kubectl -n planetarium port-forward svc/pl-web-tunnel 18090:8090` and
  `node tools/public-smoke.mjs http://127.0.0.1:18090` (every blocked path must be 404 here).
- Small load first: `node tools/load-200.mjs https://emberline.pages.dev --users 20 --seconds 60`, then
  `--users 200 --seconds 300` only on Workers Paid (below). Watch `429` (must be 0) and p95 per group.

### 8. Rollback (fastest first)

1. Public API off, LAN untouched: `kubectl -n planetarium scale deploy/emberline-tunnel --replicas=0`.
2. Worker: `npx wrangler rollback` (previous version) or delete the Worker; the Pages Function then answers 503 for
   `/api/v1/*` while static pages stay up. Pages: Deployments → previous deployment → Rollback.
3. pl-web: `git show 1760655:deploy/k8s/planetarium.yaml | kubectl apply -f -`, then
   `kubectl -n planetarium delete svc pl-web-tunnel networkpolicy/pl-web-ingress`.
4. Rate limits: `git show 1760655:deploy/k8s/planetarium-app.yaml` → deploy (removes the public numbers).
5. Tunnel for good: delete Deployment `emberline-tunnel`, Secret `emberline-tunnel`, then the tunnel in the dashboard.

## Free plan limits

- Workers Free: **100,000 requests per day** per account (reset 00:00 UTC). Every Pages Function invocation counts;
  static assets do not. Calls to `emberline-api` through the service binding are counted here as a second request to
  stay on the safe side (Cloudflare does not bill them separately on Paid).
- Profile (PUB-RL): one reviewer = 6 page views × 6 API calls + 4 pans × 20 tiles = **116 API requests per minute**
  (upper bound: browser cache hits within `max-age=60` make it lower).
- 200 reviewers = 23,200 invocations per minute. The 100,000 daily requests last 100,000 / 116 ≈ 862 reviewer-minutes,
  i.e. **≈ 4.3 minutes of 200 concurrent reviewers** (≈ 2.2 minutes if the binding hop counts too).
- Expected day: 200 reviewers × 60 min × 116 ≈ **1.39 M invocations/day** (2.78 M with the binding hop).
- Therefore Workers Paid (USD 5/month, 10 M requests/month included, USD 0.30 per extra million) is needed **before**
  opening to 200 reviewers; on Free the API fails for the rest of the UTC day once the cap is reached (static pages stay
  up). A 30-day month at the expected day ≈ 42 M requests ≈ USD 5 + 32 × 0.30 ≈ USD 15.

// Worker emberline-api: GET/HEAD /api/v1/* → env.ORIGIN (Workers VPC Service → tunnel → pl-web listener 8090).
// The client address arrives as X-Client-IP, set by the Pages Function from its own CF-Connecting-IP; it is passed on
// only when it is one IP literal. Only an allowlist of request headers is forwarded, so hop-by-hop and credential
// headers (Authorization, Cookie, Proxy-*, Connection, ...) never reach the origin.
// One POST is public: exactly /api/v1/forecast, Content-Type application/json, body ≤ FORECAST_MAX_BODY bytes
// (= emberline.limits.forecast-body-bytes), read here in full and forwarded with Content-Type: application/json.
// Any other POST/PUT/PATCH/DELETE/... is 405 with Allow: GET, HEAD.

const FORWARD = ['accept', 'accept-language', 'if-none-match', 'if-modified-since', 'user-agent'];
const HOP = ['connection', 'keep-alive', 'proxy-authenticate', 'proxy-authorization', 'te', 'trailer', 'trailers',
  'transfer-encoding', 'upgrade', 'set-cookie'];
const IPV4 = /^((25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\.){3}(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)$/;
const IPV6_CHARS = /^[0-9A-Fa-f:]{2,39}$/;
export const FORECAST_PATH = '/api/v1/forecast';
export const FORECAST_MAX_BODY = 131072;
const JSON_TYPE = /^application\/json\s*(;.*)?$/i;

export function ipLiteral(value) {
  if (typeof value !== 'string') return null;
  if (IPV4.test(value)) return value;
  if (!IPV6_CHARS.test(value) || !value.includes(':')) return null;
  try {
    new URL(`http://[${value}]/`);
    return value;
  } catch {
    return null;
  }
}

export function problem(status, title, extra = {}) {
  return new Response(JSON.stringify({ title, status }), {
    status,
    headers: { 'content-type': 'application/problem+json', 'cache-control': 'no-store', ...extra },
  });
}

export function originRequest(request, base, body = null) {
  const url = new URL(request.url);
  const headers = new Headers();
  for (const name of FORWARD) {
    const v = request.headers.get(name);
    if (v !== null) headers.set(name, v);
  }
  const client = ipLiteral(request.headers.get('x-client-ip')) ?? ipLiteral(request.headers.get('cf-connecting-ip'));
  if (client) headers.set('x-client-ip', client);
  const init = { method: request.method, headers };
  if (body !== null) {
    headers.set('content-type', 'application/json');
    init.body = body;
  }
  return new Request(new URL(url.pathname + url.search, base).toString(), init);
}

/** The whole body as an ArrayBuffer, or null when it is longer than max bytes (reading stops there). */
export async function readBody(request, max) {
  const declared = request.headers.get('content-length');
  if (declared !== null && !(Number(declared) <= max)) return null;
  if (request.body === null) return new ArrayBuffer(0);
  const reader = request.body.getReader();
  const chunks = [];
  let total = 0;
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    total += value.byteLength;
    if (total > max) {
      await reader.cancel();
      return null;
    }
    chunks.push(value);
  }
  const out = new Uint8Array(total);
  let offset = 0;
  for (const c of chunks) {
    out.set(c, offset);
    offset += c.byteLength;
  }
  return out.buffer;
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const forecast = request.method === 'POST' && url.pathname === FORECAST_PATH;
    if (request.method !== 'GET' && request.method !== 'HEAD' && !forecast) {
      return problem(405, 'Method Not Allowed', { allow: 'GET, HEAD' });
    }
    if (!url.pathname.startsWith('/api/v1/')) {
      return problem(404, 'Not Found');
    }
    let body = null;
    if (forecast) {
      if (!JSON_TYPE.test(request.headers.get('content-type') ?? '')) {
        return problem(415, 'Unsupported Media Type', { 'accept-post': 'application/json' });
      }
      body = await readBody(request, FORECAST_MAX_BODY);
      if (body === null) {
        return problem(413, 'Content Too Large');
      }
    }
    let upstream;
    try {
      upstream = await env.ORIGIN.fetch(originRequest(request, env.ORIGIN_BASE, body));
    } catch {
      return problem(502, 'Bad Gateway');
    }
    const headers = new Headers(upstream.headers);
    for (const name of HOP) headers.delete(name);
    return new Response(request.method === 'HEAD' ? null : upstream.body, { status: upstream.status, headers });
  },
};

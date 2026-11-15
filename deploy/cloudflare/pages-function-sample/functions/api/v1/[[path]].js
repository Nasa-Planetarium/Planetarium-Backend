// Pages Function for /api/v1/* of the static site: same-origin API, no CORS. Calls the Worker emberline-api through
// the service binding API; falls back to env.API_ORIGIN only when the binding is absent. The client address is sent as
// X-Client-IP from Cloudflare's own CF-Connecting-IP (any X-Client-IP the browser sent is replaced).
// One POST is passed on: exactly /api/v1/forecast, Content-Type application/json, body ≤ FORECAST_MAX_BODY
// bytes (= emberline.limits.forecast-body-bytes); every other non-GET/HEAD request is 405 with Allow: GET, HEAD.

const FORWARD = ['accept', 'accept-language', 'if-none-match', 'if-modified-since', 'user-agent'];
export const FORECAST_PATH = '/api/v1/forecast';
export const FORECAST_MAX_BODY = 131072;
const JSON_TYPE = /^application\/json\s*(;.*)?$/i;

function problem(status, title, extra = {}) {
  return new Response(JSON.stringify({ title, status }), {
    status,
    headers: { 'content-type': 'application/problem+json', 'cache-control': 'no-store', ...extra },
  });
}

/** The whole body as an ArrayBuffer, or null when it is longer than max bytes (reading stops there). */
async function readBody(request, max) {
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

export async function onRequest({ request, env }) {
  const url = new URL(request.url);
  const forecast = request.method === 'POST' && url.pathname === FORECAST_PATH;
  if (request.method !== 'GET' && request.method !== 'HEAD' && !forecast) {
    return problem(405, 'Method Not Allowed', { allow: 'GET, HEAD' });
  }
  const headers = new Headers();
  for (const name of FORWARD) {
    const v = request.headers.get(name);
    if (v !== null) headers.set(name, v);
  }
  const client = request.headers.get('cf-connecting-ip');
  if (client) headers.set('x-client-ip', client);
  const init = { method: request.method, headers };
  if (forecast) {
    if (!JSON_TYPE.test(request.headers.get('content-type') ?? '')) {
      return problem(415, 'Unsupported Media Type', { 'accept-post': 'application/json' });
    }
    const body = await readBody(request, FORECAST_MAX_BODY);
    if (body === null) {
      return problem(413, 'Content Too Large');
    }
    headers.set('content-type', 'application/json');
    init.body = body;
  }
  if (env.API && typeof env.API.fetch === 'function') {
    return env.API.fetch(new Request(url.toString(), init));
  }
  if (env.API_ORIGIN) {
    return fetch(new URL(url.pathname + url.search, env.API_ORIGIN).toString(), init);
  }
  return problem(503, 'API not configured');
}

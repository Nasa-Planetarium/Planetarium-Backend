// Worker emberline-api: GET/HEAD /api/v1/* → env.ORIGIN (Workers VPC Service → tunnel → pl-web listener 8090).
// The client address arrives as X-Client-IP, set by the Pages Function from its own CF-Connecting-IP; it is passed on
// only when it is one IP literal. Only an allowlist of request headers is forwarded, so hop-by-hop and credential
// headers (Authorization, Cookie, Proxy-*, Connection, ...) never reach the origin.

const FORWARD = ['accept', 'accept-language', 'if-none-match', 'if-modified-since', 'user-agent'];
const HOP = ['connection', 'keep-alive', 'proxy-authenticate', 'proxy-authorization', 'te', 'trailer', 'trailers',
  'transfer-encoding', 'upgrade', 'set-cookie'];
const IPV4 = /^((25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\.){3}(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)$/;
const IPV6_CHARS = /^[0-9A-Fa-f:]{2,39}$/;

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

export function originRequest(request, base) {
  const url = new URL(request.url);
  const headers = new Headers();
  for (const name of FORWARD) {
    const v = request.headers.get(name);
    if (v !== null) headers.set(name, v);
  }
  const client = ipLiteral(request.headers.get('x-client-ip')) ?? ipLiteral(request.headers.get('cf-connecting-ip'));
  if (client) headers.set('x-client-ip', client);
  return new Request(new URL(url.pathname + url.search, base).toString(), { method: request.method, headers });
}

export default {
  async fetch(request, env) {
    if (request.method !== 'GET' && request.method !== 'HEAD') {
      return problem(405, 'Method Not Allowed', { allow: 'GET, HEAD' });
    }
    const url = new URL(request.url);
    if (!url.pathname.startsWith('/api/v1/')) {
      return problem(404, 'Not Found');
    }
    let upstream;
    try {
      upstream = await env.ORIGIN.fetch(originRequest(request, env.ORIGIN_BASE));
    } catch {
      return problem(502, 'Bad Gateway');
    }
    const headers = new Headers(upstream.headers);
    for (const name of HOP) headers.delete(name);
    return new Response(request.method === 'HEAD' ? null : upstream.body, { status: upstream.status, headers });
  },
};

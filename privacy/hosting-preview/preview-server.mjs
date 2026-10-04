import http from 'node:http';
import { readFile } from 'node:fs/promises';

// Loopback-only preview; no directory listing, API or arbitrary file access.
const pages = new Map([
  ['/', ['index.html', 'text/html; charset=utf-8']],
  ['/index.html', ['index.html', 'text/html; charset=utf-8']],
  ['/robots.txt', ['robots.txt', 'text/plain; charset=utf-8']],
]);
const publicRoot = new URL('./public/', import.meta.url);
const server = http.createServer(async (request, response) => {
  const route = new URL(request.url ?? '/', 'http://127.0.0.1').pathname;
  const page = pages.get(route);
  if (!page || !['GET', 'HEAD'].includes(request.method ?? 'GET')) {
    response.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
    response.end('Not found');
    return;
  }
  try {
    const contents = await readFile(new URL(page[0], publicRoot));
    response.writeHead(200, {
      'Content-Type': page[1],
      'Content-Length': contents.byteLength,
      'Cache-Control': 'no-store',
      'X-Content-Type-Options': 'nosniff',
      'X-Frame-Options': 'DENY',
      'Referrer-Policy': 'no-referrer',
      'Permissions-Policy': 'camera=(), geolocation=(), microphone=()',
      'Content-Security-Policy': "default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'",
    });
    response.end(request.method === 'HEAD' ? undefined : contents);
  } catch {
    response.writeHead(500, { 'Content-Type': 'text/plain; charset=utf-8' });
    response.end('Preview unavailable');
  }
});
server.listen(8765, '127.0.0.1', () => {
  process.stdout.write('Policy review preview: http://127.0.0.1:8765/ (local only)\n');
});

import { createServer as createNetServer } from 'node:net';
import { createServer, type IncomingMessage } from 'node:http';
import { chmodSync, unlinkSync, existsSync, statSync, createReadStream } from 'node:fs';
import { randomUUID, createHash, timingSafeEqual } from 'node:crypto';
import { WebSocketServer } from 'ws';
import { DEFAULT_WS_PORT } from '@mobilemcp/shared';
import { StreamableHTTPServerTransport } from '@modelcontextprotocol/sdk/server/streamableHttp.js';
import { isInitializeRequest } from '@modelcontextprotocol/sdk/types.js';
import { DeviceRouter, validHello, type HubEvent } from './router.js';
import dashboardHtml from '../dashboard/index.html';
import picoCss from '../dashboard/vendor/pico.min.css';
import { createMcpServer } from './mcp-server.js';
import type { Bridge } from './bridge.js';

export const HUB_SOCK = process.env.MOBILEMCP_HUB_SOCK ?? '/tmp/mobilemcp-hub.sock';
const host = process.env.MOBILEMCP_HOST ?? '127.0.0.1';
const port = Number(process.env.MOBILEMCP_PORT ?? DEFAULT_WS_PORT);
const loopback = ['127.0.0.1', 'localhost', '::1'].includes(host);
const publicUrl = process.env.MOBILEMCP_PUBLIC_URL ? new URL(process.env.MOBILEMCP_PUBLIC_URL) : undefined;
if (publicUrl && (publicUrl.protocol !== 'https:' || publicUrl.pathname !== '/' || publicUrl.search || publicUrl.hash || publicUrl.username || publicUrl.password)) throw new Error('MOBILEMCP_PUBLIC_URL must be an HTTPS origin, e.g. https://mobile.example.com');

type Account = { id: string; agentToken: string; deviceToken: string; router: DeviceRouter; events: HubEvent[]; listeners: Set<(e: HubEvent) => void>; dashboard?: Bridge };
const HUB_VERSION = '0.4.0'; const startedAt = Date.now();
const config = process.env.MOBILEMCP_ACCOUNTS;
const accounts: Account[] = (config ? JSON.parse(config) : process.env.MOBILEMCP_TOKEN ? [{ id: 'default', agentToken: process.env.MOBILEMCP_TOKEN, deviceToken: process.env.MOBILEMCP_TOKEN }] : []).map((a: any) => ({ ...a, events: [], listeners: new Set() }));
if (!Array.isArray(accounts)) throw new Error('MOBILEMCP_ACCOUNTS must be a JSON array');
const authenticated = accounts.length > 0;
if ((!loopback || publicUrl) && !authenticated) throw new Error('Hosted/network access requires MOBILEMCP_TOKEN or MOBILEMCP_ACCOUNTS');
const ids = new Set<string>(), tokens = new Map<string, string>();
for (const a of accounts) {
  if (!a || typeof a.id !== 'string' || !a.id || ids.has(a.id)) throw new Error('Account IDs must be unique nonempty strings');
  ids.add(a.id);
  for (const token of [a.agentToken, a.deviceToken]) {
    if (typeof token !== 'string' || token.length < 32 || /\s/.test(token)) throw new Error('Access tokens must contain at least 32 non-whitespace characters');
    if (tokens.has(token) && tokens.get(token) !== a.id) throw new Error('Tokens cannot be shared between accounts');
    tokens.set(token, a.id);
  }
  a.router = new DeviceRouter(); wireEvents(a);
}
const local: Account = { id: 'local', agentToken: '', deviceToken: '', router: new DeviceRouter(), events: [], listeners: new Set() };
wireEvents(local);
function wireEvents(a: Account) { a.router.onEvent = e => { a.events.push(e); if (a.events.length > 300) a.events.splice(0, a.events.length - 300); for (const l of a.listeners) l(e); }; }
const hash = (s: string) => createHash('sha256').update(s).digest();
function authenticate(value: unknown, role: 'agentToken' | 'deviceToken'): Account | undefined {
  if (!authenticated) return local;
  if (typeof value !== 'string' || value.length > 4096) return;
  const digest = hash(value);
  return accounts.find(a => timingSafeEqual(hash(a[role]), digest));
}
function requestAllowed(req: IncomingMessage) {
  // Authenticated network hubs accept any Host (the phone connects through LAN IPs, tunnels or a reverse proxy).
  // Loopback hubs pin Host to local names plus the emulator's host alias 10.0.2.2.
  if (!loopback && !publicUrl) return true;
  const allowedHosts = new Set([publicUrl?.host, `localhost:${port}`, `127.0.0.1:${port}`, `[::1]:${port}`, `10.0.2.2:${port}`]);
  if (!allowedHosts.has(req.headers.host)) return false;
  const origin = req.headers.origin;
  if (!origin) return true;
  if (publicUrl && origin === publicUrl.origin) return true;
  return loopback && [`http://localhost:${port}`, `http://127.0.0.1:${port}`, `http://[::1]:${port}`].includes(origin);
}

type HttpSession = { owner: string; transport: StreamableHTTPServerTransport; bridge: Bridge; touched: number };
const httpSessions = new Map<string, HttpSession>();
const httpServer = createServer(async (req, res) => {
  const respond = (code: number, message: string) => { res.writeHead(code, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' }); res.end(JSON.stringify({ error: message })); };
  if (!requestAllowed(req)) { respond(403, 'Host or Origin not allowed'); return; }
  if (req.url === '/healthz' && req.method === 'GET') { res.writeHead(200, { 'Content-Type': 'application/json' }); res.end(JSON.stringify({ status: 'ok', devices: authenticated ? undefined : local.router.list().length })); return; }
  if (new URL(req.url ?? '/', 'http://localhost').pathname === '/app.apk' && req.method === 'GET') {
    // Self-update path: phones download the app from their own hub. Device token required on authenticated hubs.
    const apk = process.env.MOBILEMCP_APK;
    if (!apk || !existsSync(apk)) { respond(404, 'No APK configured (MOBILEMCP_APK)'); return; }
    if (!authenticate(new URL(req.url ?? '/', 'http://localhost').searchParams.get('token') ?? undefined, 'deviceToken')) { respond(401, 'Valid device token required (?token=)'); return; }
    res.writeHead(200, { 'Content-Type': 'application/vnd.android.package-archive', 'Content-Disposition': 'attachment; filename="mobilemcp.apk"', 'Content-Length': statSync(apk).size, 'Cache-Control': 'no-store' });
    createReadStream(apk).pipe(res); return;
  }
  const url = new URL(req.url ?? '/', 'http://localhost');
  if ((url.pathname === '/' || url.pathname === '/dashboard') && req.method === 'GET') {
    res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-store', 'Content-Security-Policy': "default-src 'self'; img-src 'self' data:; style-src 'unsafe-inline'; script-src 'unsafe-inline'; connect-src 'self'" });
    res.end(dashboardHtml.replace('/*PICO*/', picoCss).replace('{{AUTH}}', authenticated ? 'token' : 'none').replace('{{VERSION}}', HUB_VERSION)); return;
  }
  if (url.pathname.startsWith('/api/')) {
    // Dashboard API: agent token as Bearer, or ?token= for EventSource which cannot set headers.
    const header = req.headers.authorization; const token = header?.startsWith('Bearer ') ? header.slice(7) : url.searchParams.get('token') ?? undefined;
    const account = authenticate(token, 'agentToken');
    if (!account) { respond(401, 'Valid agent token required'); return; }
    if (url.pathname === '/api/status' && req.method === 'GET') {
      res.writeHead(200, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' });
      res.end(JSON.stringify({ hub: { version: HUB_VERSION, authenticated, uptimeMs: Date.now() - startedAt, account: account.id }, devices: account.router.list(), sessions: account.router.sessionCount(), events: account.events.slice(-100) })); return;
    }
    if (url.pathname === '/api/events' && req.method === 'GET') {
      res.writeHead(200, { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-store', Connection: 'keep-alive' });
      res.write(': connected\n\n');
      const listener = (e: HubEvent) => res.write(`data: ${JSON.stringify(e)}\n\n`);
      account.listeners.add(listener);
      const ping = setInterval(() => res.write(': ping\n\n'), 25000);
      req.on('close', () => { account.listeners.delete(listener); clearInterval(ping); }); return;
    }
    if (url.pathname === '/api/call' && req.method === 'POST') {
      const chunks: Buffer[] = []; let size = 0;
      for await (const chunk of req) { size += chunk.length; if (size > 1024 * 1024) { respond(413, 'Request too large'); return; } chunks.push(Buffer.from(chunk)); }
      let body: any; try { body = JSON.parse(Buffer.concat(chunks).toString('utf8')); } catch { respond(400, 'Invalid JSON'); return; }
      if (!body || typeof body.action !== 'string') { respond(400, 'action required'); return; }
      try {
        account.dashboard ??= account.router.session();
        if (typeof body.deviceId === 'string') await account.dashboard.selectDevice(body.deviceId);
        const result = body.action === 'hub.listDevices' ? await account.dashboard.listDevices() : await account.dashboard.request(body.action, body.params ?? {});
        res.writeHead(200, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' }); res.end(JSON.stringify({ result }));
      } catch (e) { res.writeHead(200, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' }); res.end(JSON.stringify({ error: e instanceof Error ? e.message : String(e) })); }
      return;
    }
    respond(404, 'Not found'); return;
  }
  if (url.pathname !== '/mcp') { respond(404, 'Not found'); return; }
  const authorization = req.headers.authorization;
  const account = authenticate(authorization?.startsWith('Bearer ') ? authorization.slice(7) : undefined, 'agentToken');
  if (!account) { res.setHeader('WWW-Authenticate', 'Bearer realm="mobilemcp"'); respond(401, 'Valid bearer token required'); return; }
  if (!['POST', 'GET', 'DELETE'].includes(req.method ?? '')) { res.setHeader('Allow', 'POST, GET, DELETE'); respond(405, 'Method not allowed'); return; }
  let fresh: HttpSession | undefined;
  try {
    const sessionId = req.headers['mcp-session-id'];
    let session = typeof sessionId === 'string' ? httpSessions.get(sessionId) : undefined;
    if (sessionId && (!session || session.owner !== account.id)) { respond(404, 'Session not found'); return; }
    let body: unknown;
    if (req.method === 'POST') {
      if (!req.headers['content-type']?.split(';')[0].trim().match(/^application\/json$/i)) { respond(415, 'Use application/json'); return; }
      const chunks: Buffer[] = []; let size = 0;
      for await (const chunk of req) { size += chunk.length; if (size > 4 * 1024 * 1024) { respond(413, 'Request too large'); return; } chunks.push(Buffer.from(chunk)); }
      try { body = JSON.parse(Buffer.concat(chunks).toString('utf8')); } catch { respond(400, 'Invalid JSON'); return; }
    }
    if (!session) {
      if (sessionId || req.method !== 'POST' || !isInitializeRequest(body)) { respond(400, 'Initialize an MCP session first'); return; }
      if (httpSessions.size >= 1000 || [...httpSessions.values()].filter(s => s.owner === account.id).length >= 100) { respond(503, 'Session limit reached'); return; }
      const bridge = account.router.session();
      const transport = new StreamableHTTPServerTransport({ sessionIdGenerator: () => randomUUID(), enableJsonResponse: true, onsessioninitialized: id => { httpSessions.set(id, fresh!); } });
      session = fresh = { owner: account.id, transport, bridge, touched: Date.now() };
      const mcp = createMcpServer(bridge);
      await mcp.connect(transport);
      const onclose = transport.onclose;
      transport.onclose = () => { if (transport.sessionId) httpSessions.delete(transport.sessionId); void bridge.close(); onclose?.(); };
    }
    session.touched = Date.now();
    res.setHeader('Cache-Control', 'no-store');
    await session.transport.handleRequest(req, res, body);
    if (fresh && !fresh.transport.sessionId) { await fresh.transport.close(); await fresh.bridge.close(); }
  } catch {
    if (fresh) { await fresh.transport.close(); await fresh.bridge.close(); }
    if (!res.headersSent) respond(500, 'MCP request failed'); else res.end();
  }
});
httpServer.requestTimeout = 90000;
httpServer.headersTimeout = 15000;
const expiry = setInterval(() => { for (const s of httpSessions.values()) if (Date.now() - s.touched > 30 * 60 * 1000) void s.transport.close(); }, 60000);
expiry.unref();

const wss = new WebSocketServer({ noServer: true, maxPayload: 32 * 1024 * 1024, perMessageDeflate: false });
httpServer.on('upgrade', (req, sock, head) => {
  const path = req.url?.split('?')[0];
  if (!requestAllowed(req) || (path !== '/device' && (authenticated || path !== '/'))) { sock.end('HTTP/1.1 403 Forbidden\r\nConnection: close\r\n\r\n'); return; }
  if (wss.clients.size >= 1000) { sock.end('HTTP/1.1 503 Service Unavailable\r\nConnection: close\r\n\r\n'); return; }
  wss.handleUpgrade(req, sock, head, ws => {
    ws.on('error', () => {});
    if (!authenticated) { local.router.attach(ws); return; }
    const timeout = setTimeout(() => ws.close(1008, 'Authentication timeout'), 5000);
    ws.on('close', () => clearTimeout(timeout));
    ws.once('message', raw => {
      clearTimeout(timeout);
      let hello: any; try { hello = JSON.parse(raw.toString()); } catch {}
      const account = validHello(hello) ? authenticate(hello.token, 'deviceToken') : undefined;
      if (!account) { ws.close(1008, 'Invalid device access token or hello'); return; }
      account.router.attach(ws, hello);
    });
  });
});

// IPC for local stdio MCP processes. Never unlink another running hub's socket.
let ownsSocket = false;
const ipcServer = createNetServer(sock => {
  sock.setEncoding('utf8'); let buffer = '';
  const sessions = new Map<string, Bridge>();
  const active = new Set<string>();
  const send = (message: object) => { if (!sock.destroyed) sock.write(JSON.stringify(message) + '\n'); };
  sock.on('data', chunk => {
    buffer += chunk;
    if (buffer.length > 4 * 1024 * 1024) { sock.destroy(); return; }
    let end: number;
    while ((end = buffer.indexOf('\n')) >= 0) {
      const line = buffer.slice(0, end); buffer = buffer.slice(end + 1);
      let msg: any; try { msg = JSON.parse(line); } catch { continue; }
      if (!msg || typeof msg.sessionId !== 'string') continue;
      if (msg.type === 'register') {
        const account = authenticate(msg.token, 'agentToken');
        if (!account || sessions.size >= 100) { sock.destroy(); return; }
        if (!sessions.has(msg.sessionId)) sessions.set(msg.sessionId, account.router.session(send, typeof msg.deviceId === 'string' ? msg.deviceId : undefined));
        send({ type: 'registered', sessionId: msg.sessionId }); continue;
      }
      const bridge = sessions.get(msg.sessionId); if (!bridge) continue;
      if (msg.type === 'unregister') { void bridge.close(); sessions.delete(msg.sessionId); continue; }
      const key = `${msg.sessionId}:${msg.id}`;
      if (msg.type === 'cancel') { active.delete(key); continue; }
      if (msg.type !== 'request' || typeof msg.id !== 'string' || active.has(key)) continue;
      active.add(key);
      const promise = msg.action === 'hub.listDevices' ? bridge.listDevices() : msg.action === 'hub.selectDevice' ? bridge.selectDevice(msg.params?.deviceId) : bridge.request(msg.action, msg.params ?? {});
      void promise.then(result => { if (active.delete(key)) send({ type: 'response', id: msg.id, result }); }, error => { if (active.delete(key)) send({ type: 'response', id: msg.id, error: error.message }); });
    }
  });
  sock.on('close', () => { active.clear(); for (const bridge of sessions.values()) void bridge.close(); });
  sock.on('error', () => {});
});
ipcServer.on('error', error => { process.stderr.write(`[mobilemcp-hub] IPC: ${error.message}. Stop the existing hub or use another MOBILEMCP_HUB_SOCK; remove stale sockets only after checking the hub is stopped.\n`); shutdown(1); });
if (process.env.MOBILEMCP_DISABLE_IPC !== '1') ipcServer.listen(HUB_SOCK, () => { ownsSocket = true; chmodSync(HUB_SOCK, 0o600); });
httpServer.on('error', error => { process.stderr.write(`[mobilemcp-hub] ${error.message}\n`); shutdown(1); });
httpServer.listen(port, host, () => process.stderr.write(`[mobilemcp-hub] Ready — HTTP /mcp and WebSocket /device on ${host}:${port}; ${authenticated ? 'authenticated' : 'local only'}\n`));
function shutdown(code = 0) {
  clearInterval(expiry); for (const s of httpSessions.values()) void s.transport.close();
  for (const a of [...accounts, local]) a.router.close();
  ipcServer.close(); wss.close(); httpServer.close();
  if (ownsSocket) { try { unlinkSync(HUB_SOCK); } catch {} }
  process.exit(code);
}
process.on('SIGTERM', () => shutdown()); process.on('SIGINT', () => shutdown());

import { createConnection, type Socket } from 'node:net';
import { randomUUID } from 'node:crypto';
import { executionBudget } from '@mobilemcp/shared';
import type { Bridge } from './bridge.js';
export const HUB_SOCK = process.env.MOBILEMCP_HUB_SOCK ?? '/tmp/mobilemcp-hub.sock';
type Pending = { resolve: (value: unknown) => void; reject: (reason: Error) => void; timer: ReturnType<typeof setTimeout> };
/** Stdio MCP processes talk to the always-on hub over a local IPC socket. */
export function createHubClient(): Bridge {
  const sessionId = randomUUID(), pending = new Map<string, Pending>();
  let sock: Socket, hubConnected = false, deviceConnected = false, closed = false, attempt = 0;
  let selectedDeviceId: string | undefined;
  let reconnect: ReturnType<typeof setTimeout> | undefined;
  const failPending = (message: string) => { for (const e of pending.values()) { clearTimeout(e.timer); e.reject(new Error(message)); } pending.clear(); };
  const send = (msg: object) => sock.write(JSON.stringify(msg) + '\n');
  function connect() {
    if (closed) return;
    const connection = createConnection(HUB_SOCK); connection.setEncoding('utf8'); sock = connection;
    let buf = '';
    connection.on('connect', () => { hubConnected = true; attempt = 0; send({ type: 'register', sessionId, deviceId: selectedDeviceId, token: process.env.MOBILEMCP_TOKEN }); });
    connection.on('data', chunk => {
      buf += chunk.toString();
      if (buf.length > 32 * 1024 * 1024) { connection.destroy(new Error('Hub response exceeded 32 MB')); return; }
      let end: number;
      while ((end = buf.indexOf('\n')) >= 0) {
        const line = buf.slice(0, end); buf = buf.slice(end + 1);
        let msg: any; try { msg = JSON.parse(line); } catch { continue; }
        if (msg.type === 'status') { deviceConnected = Boolean(msg.connected); if (typeof msg.deviceId === 'string') selectedDeviceId = msg.deviceId; }
        else if (msg.type === 'response') {
          const slot = pending.get(msg.id); if (!slot) continue;
          clearTimeout(slot.timer); pending.delete(msg.id);
          msg.error !== undefined ? slot.reject(new Error(String(msg.error))) : slot.resolve(msg.result);
        }
      }
    });
    connection.on('close', () => {
      hubConnected = false; deviceConnected = false;
      failPending('Hub disconnected; action outcome may be unknown.');
      if (!closed) { reconnect = setTimeout(connect, Math.min(5000, 250 * 2 ** attempt++)); reconnect.unref(); }
    });
    connection.on('error', error => { if (attempt === 0) process.stderr.write(`[mobilemcp] ${error.message}; reconnecting without replaying actions.\n`); });
  }
  connect();
  const request = (action: string, params: Record<string, unknown> = {}) => {
    if (!hubConnected) return Promise.reject(new Error('HUB_UNAVAILABLE: Hub unavailable; start mobilemcp-hub. Connection recovers automatically.'));
    if (!deviceConnected && !action.startsWith('hub.')) return Promise.reject(new Error('NO_DEVICE: No phone connected to the hub. Open the MobileMCP app, enable its accessibility service and connect.'));
    if (pending.size >= 50) return Promise.reject(new Error('Too many outstanding device requests.'));
    const id = randomUUID();
    return new Promise<unknown>((resolve, reject) => {
      const timer = setTimeout(() => { pending.delete(id); send({ type: 'cancel', sessionId, id }); reject(new Error('BRIDGE_TIMEOUT: Device request timed out; inspect the screen before retrying.')); }, executionBudget(action, params) + 3000);
      pending.set(id, { resolve, reject, timer }); send({ type: 'request', sessionId, id, action, params });
    });
  };
  return {
    request, listDevices: () => request('hub.listDevices'), selectDevice: deviceId => request('hub.selectDevice', { deviceId }),
    isConnected: () => hubConnected && deviceConnected,
    close: async () => { closed = true; clearTimeout(reconnect); if (hubConnected) send({ type: 'unregister', sessionId }); failPending('Bridge closed'); sock.destroy(); },
  };
}

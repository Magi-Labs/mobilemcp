import { randomUUID } from 'node:crypto';
import { WebSocket } from 'ws';
import { BRIDGE_ACTIONS, isBridgeResponse, executionBudget } from '@mobilemcp/shared';
import type { Bridge } from './bridge.js';

type Device = { id: string; name: string; model?: string; ws: WebSocket; connectedAt: string; lastAction?: string; lastActionAt?: string; requests: number };
/** Hub activity for the dashboard: identities, action names, durations and error codes only, never params or results. */
export type HubEvent = { t: string; type: 'device.connected' | 'device.disconnected' | 'request' | 'response'; deviceId?: string; device?: string; action?: string; id?: string; ms?: number; ok?: boolean; code?: string };
type Session = { deviceId?: string; notify: (message: object) => void };
type Pending = { session: Session; device: Device; action: string; started: number; resolve: (value: unknown) => void; reject: (error: Error) => void; timer: ReturnType<typeof setTimeout> };

export type Hello = { type: 'hello'; deviceId: string; name?: string; model?: string; token?: string };
export function validHello(msg: any): msg is Hello {
  return msg?.type === 'hello' && typeof msg.deviceId === 'string' && /^[a-zA-Z0-9_-]{8,100}$/.test(msg.deviceId)
    && (msg.name === undefined || typeof msg.name === 'string') && (msg.model === undefined || typeof msg.model === 'string');
}

/** One router per account: device IDs and selections never cross account boundaries. */
export class DeviceRouter {
  onEvent: (e: HubEvent) => void = () => {};
  private emit(e: Omit<HubEvent, 't'>) { try { this.onEvent({ t: new Date().toISOString(), ...e }); } catch {} }
  private devices = new Map<string, Device>();
  private sessions = new Set<Session>();
  private pending = new Map<string, Pending>();

  list() { return [...this.devices.values()].filter(d => d.ws.readyState === WebSocket.OPEN).map(({ id, name, model, connectedAt, lastAction, lastActionAt, requests }) => ({ id, name, model, connectedAt, lastAction, lastActionAt, requests })); }
  sessionCount() { return this.sessions.size; }
  private status(s: Session) { s.notify({ type: 'status', connected: this.list().length > 0, deviceId: s.deviceId }); }
  private broadcast() { for (const s of this.sessions) this.status(s); }
  private finish(id: string, error?: string, result?: unknown) {
    const p = this.pending.get(id); if (!p) return;
    clearTimeout(p.timer); this.pending.delete(id);
    this.emit({ type: 'response', id, deviceId: p.device.id, device: p.device.name, action: p.action, ms: Date.now() - p.started, ok: error === undefined, code: error !== undefined ? (/^([A-Z][A-Z_]{2,}):/.exec(error)?.[1] ?? 'ERROR') : undefined });
    error !== undefined ? p.reject(new Error(error)) : p.resolve(result);
  }
  private remove(device: Device) {
    if (this.devices.get(device.id) !== device) return;
    this.devices.delete(device.id);
    this.emit({ type: 'device.disconnected', deviceId: device.id, device: device.name });
    for (const [id, p] of this.pending) if (p.device === device) this.finish(id, 'DEVICE_DISCONNECTED: Device disconnected; action outcome may be unknown.');
    this.broadcast();
  }
  /** Attach a device socket. `hello` is required when the hub already authenticated it; otherwise the first message must be a hello. */
  attach(ws: WebSocket, hello?: Hello) {
    let device: Device | undefined;
    const register = (h: Hello) => {
      const previous = this.devices.get(h.deviceId);
      if (previous && previous.ws !== ws) { this.remove(previous); previous.ws.close(1000, 'Device reconnected'); }
      device = { id: h.deviceId, name: h.name?.slice(0, 80) || 'Android', model: h.model?.slice(0, 80), ws, connectedAt: new Date().toISOString(), requests: 0 };
      this.devices.set(device.id, device); this.broadcast();
      this.emit({ type: 'device.connected', deviceId: device.id, device: device.name });
      ws.send(JSON.stringify({ type: 'hello_ack', deviceId: device.id }));
    };
    if (hello) register(hello);
    let alive = true;
    const ping = setInterval(() => { if (!alive) { ws.terminate(); return; } alive = false; ws.ping(); }, 25000);
    ws.on('pong', () => { alive = true; });
    ws.on('message', raw => {
      let msg: any; try { msg = JSON.parse(raw.toString()); } catch { return; }
      if (msg?.type === 'hello') {
        if (!validHello(msg) || (device && device.id !== msg.deviceId)) { ws.close(1008, 'Invalid device identity'); return; }
        register(msg); return;
      }
      if (msg?.type === 'ping') { ws.send('{"type":"pong"}'); return; }
      if (!device || !isBridgeResponse(msg)) return;
      const p = this.pending.get(msg.id);
      if (p?.device === device && this.devices.get(device.id) === device) this.finish(msg.id, msg.error, msg.result);
    });
    const cleanup = () => { clearInterval(ping); if (device) this.remove(device); };
    ws.on('close', cleanup); ws.on('error', cleanup);
  }
  session(notify: (message: object) => void = () => {}, deviceId?: string): Bridge {
    const s: Session = { notify, deviceId }; this.sessions.add(s); this.status(s);
    let closed = false;
    return {
      isConnected: () => !closed && this.list().length > 0,
      listDevices: async () => ({ devices: this.list(), selectedDeviceId: s.deviceId ?? null }),
      selectDevice: async id => {
        if (closed || !this.devices.has(id)) throw new Error('DEVICE_UNAVAILABLE: Device unavailable. Call list_devices for connected devices.');
        s.deviceId = id; this.status(s); const d = this.devices.get(id)!; return { deviceId: id, name: d.name, model: d.model };
      },
      request: async (action, params = {}) => {
        if (closed) throw new Error('Session closed');
        if (!(BRIDGE_ACTIONS as readonly string[]).includes(action)) throw new Error('Unknown device action');
        if (!s.deviceId) {
          const list = this.list();
          if (list.length !== 1) throw new Error(list.length ? 'SELECT_DEVICE: Multiple devices connected. Call list_devices then select_device.' : 'NO_DEVICE: No phone connected to the hub. Open the MobileMCP app, enable its accessibility service and connect.');
          s.deviceId = list[0].id; this.status(s);
        }
        const device = this.devices.get(s.deviceId);
        if (!device || device.ws.readyState !== WebSocket.OPEN) throw new Error('DEVICE_DISCONNECTED: Selected device disconnected. Reconnect it or select_device; no fallback to another device.');
        if (this.pending.size >= 1000 || [...this.pending.values()].filter(p => p.session === s).length >= 50) throw new Error('Hub busy; retry later.');
        return new Promise((resolve, reject) => {
          const id = randomUUID(), budget = executionBudget(action, params);
          device.requests++; device.lastAction = action; device.lastActionAt = new Date().toISOString();
          this.emit({ type: 'request', id, deviceId: device.id, device: device.name, action });
          this.pending.set(id, { session: s, device, action, started: Date.now(), resolve, reject, timer: setTimeout(() => this.finish(id, 'BRIDGE_TIMEOUT: Device did not answer within the deadline; inspect the screen before retrying.'), budget + 1000) });
          device.ws.send(JSON.stringify({ id, action, params: { ...params, __deadline: Date.now() + budget } }), err => { if (err) this.finish(id, 'Device send failed; action outcome may be unknown.'); });
        });
      },
      close: async () => {
        closed = true; this.sessions.delete(s);
        for (const [id, p] of this.pending) if (p.session === s) this.finish(id, 'Session closed; action outcome may be unknown.');
      },
    };
  }
  close() { for (const d of this.devices.values()) d.ws.terminate(); for (const id of this.pending.keys()) this.finish(id, 'Hub shutting down'); }
}

export const DEFAULT_WS_PORT = 17692;

/** Actions the Android app executes. The hub forwards them verbatim; the server exposes tools on top. */
export const BRIDGE_ACTIONS = [
  'device.info',
  'screen.snapshot',
  'screen.screenshot',
  'screen.waitFor',
  'screen.readText',
  'interact.tap',
  'interact.type',
  'interact.swipe',
  'interact.drag',
  'interact.pinch',
  'interact.scroll',
  'interact.scrollUntil',
  'interact.key',
  'interact.setClipboard',
  'apps.list',
  'apps.open',
  'apps.openUrl',
  'apps.uninstall',
  'notifications.list',
  'notifications.open',
  'notifications.act',
  'notifications.dismiss',
  'system.openSettings',
  'system.startIntent',
  'system.media',
  'system.volume',
  'system.brightness',
  'system.rotation',
  'system.dnd',
  'system.flashlight',
  'system.wake',
  'device.batch',
] as const;

export type BridgeAction = (typeof BRIDGE_ACTIONS)[number];

export type BridgeRequest = { id: string; action: BridgeAction; params?: Record<string, unknown> };
export type BridgeResponse = { id: string; result?: unknown; error?: string };

export function isBridgeResponse(v: unknown): v is BridgeResponse {
  if (!v || typeof v !== 'object') return false;
  const o = v as Record<string, unknown>;
  return typeof o.id === 'string' && ('result' in o || 'error' in o);
}

const clamp = (v: unknown, lo: number, hi: number, dflt: number) => Math.min(hi, Math.max(lo, Number(v ?? dflt) || dflt));

/** Execution budget per action in ms. The app receives `__deadline` and stops polling/waiting at it. */
export function executionBudget(action: string, params: Record<string, any>): number {
  if (action === 'device.batch') return clamp(params.timeout, 1000, 60000, 60000);
  if (action === 'screen.waitFor') return clamp(params.timeout, 1000, 60000, 10000) + 2000;
  if (action === 'interact.scrollUntil') return clamp(params.timeout, 1000, 60000, 20000) + 2000;
  if (action === 'screen.screenshot') return 15000;
  return 20000;
}

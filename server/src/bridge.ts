import type { BridgeAction } from '@mobilemcp/shared';
/** One agent session's view of the hub: a selected device and a request channel to it. */
export type Bridge = {
  listDevices: () => Promise<unknown>;
  selectDevice: (deviceId: string) => Promise<unknown>;
  isConnected: () => boolean;
  request: (action: BridgeAction, params?: Record<string, unknown>) => Promise<unknown>;
  close: () => Promise<void>;
};

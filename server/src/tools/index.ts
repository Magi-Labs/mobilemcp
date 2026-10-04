import type { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js';
import type { Bridge } from '../bridge.js';
import { registerDeviceTools } from './devices.js';
import { registerObserveTools } from './observe.js';
import { registerInteractTools } from './interact.js';
import { registerAppTools } from './apps.js';
import { registerBatchTools } from './batch.js';
export function registerAllTools(mcp: McpServer, bridge: Bridge): void {
  registerDeviceTools(mcp, bridge);
  registerObserveTools(mcp, bridge);
  registerInteractTools(mcp, bridge);
  registerAppTools(mcp, bridge);
  registerBatchTools(mcp, bridge);
}

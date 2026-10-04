import type { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js';
import type { Bridge } from '../bridge.js';
import { z } from 'zod';
import { actionObservationSchema, bridgeCall } from './helpers.js';
export function registerAppTools(mcp: McpServer, bridge: Bridge): void {
  mcp.registerTool('list_apps', {
    description: 'Installed launchable apps as {package,label}. Filter with query.',
    inputSchema: { query: z.string().max(100).optional(), limit: z.number().int().min(1).max(500).optional().describe('Default 100.') },
  }, async args => bridgeCall(bridge, 'apps.list', args));
  mcp.registerTool('open_app', {
    description: 'Launch an app by package name or unique label substring, then return its first screen.',
    inputSchema: { app: z.string().min(1).max(200).describe('Package like com.whatsapp or label like "WhatsApp".'), ...actionObservationSchema },
  }, async args => bridgeCall(bridge, 'apps.open', args));
  mcp.registerTool('open_url', {
    description: 'Open a URL or deep link (https://, tel:, geo:, app schemes) with the phone\'s default handler.',
    inputSchema: { url: z.string().min(1).max(4000), ...actionObservationSchema },
  }, async args => bridgeCall(bridge, 'apps.openUrl', args));
}

import type { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js';
import type { Bridge } from '../bridge.js';
import { z } from 'zod';
import { observationSchema, bridgeCall, bridgeImageCall } from './helpers.js';
export function registerObserveTools(mcp: McpServer, bridge: Bridge): void {
  mcp.registerTool('get_screen_snapshot', {
    description: 'Bounded accessibility tree of the current screen: one line per node "@ref role \"text\" [states] (x,y wxh)" with foreground app, version and truncation info. Supports query, scope, interactiveOnly, paging and since-deltas. Optional screenshot image.',
    inputSchema: { ...observationSchema, screenshot: z.boolean().optional().describe('Also return a scaled JPEG of the screen.') },
  }, async args => bridgeImageCall(bridge, 'screen.snapshot', args));
  mcp.registerTool('take_screenshot', {
    description: 'JPEG screenshot of the phone screen, scaled down (default max 800px wide). Use for visual content or when the tree is insufficient. FLAG_SECURE apps return black.',
    inputSchema: {
      maxWidth: z.number().int().min(100).max(2000).optional().describe('Default 800.'),
      quality: z.number().int().min(10).max(100).optional().describe('JPEG quality, default 70.'),
    },
  }, async args => bridgeImageCall(bridge, 'screen.screenshot', args));
  mcp.registerTool('wait_for_text', {
    description: 'Wait until text appears (or disappears with gone:true) anywhere on screen, or until an app package is in the foreground. Returns the matching node. Up to 60 seconds.',
    inputSchema: {
      text: z.string().max(500).optional().describe('Case-insensitive substring of node text or description.'),
      package: z.string().max(200).optional().describe('Wait for this app package to be in the foreground.'),
      gone: z.boolean().optional().describe('Wait for the text to disappear instead.'),
      timeout: z.number().int().min(1000).max(60000).optional().describe('Milliseconds, default 10000.'),
    },
  }, async args => bridgeCall(bridge, 'screen.waitFor', args));
}

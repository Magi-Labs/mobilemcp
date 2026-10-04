import type { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js';
import type { Bridge } from '../bridge.js';
import { z } from 'zod';
import { observationSchema, refSchema, bridgeCall, bridgeImageCall } from './helpers.js';
export function registerObserveTools(mcp: McpServer, bridge: Bridge): void {
  mcp.registerTool('get_screen_snapshot', {
    description: 'Bounded accessibility tree of the current screen, one line per node: @ref role "text" (desc) [flags] (cx,cy). Roles imply clickability; rows show a ~"title · subtitle" label and hide their repeated children; coordinates are centers (bounds:"full" for rects). Supports query, scope, interactiveOnly, paging and since-deltas. screenshot:true adds a JPEG; marks:true labels it with the @refs (Set-of-Mark).',
    inputSchema: { ...observationSchema, bounds: z.enum(['center', 'full']).optional(), verbose: z.boolean().optional().describe('Keep every text child under labelled rows.'), screenshot: z.boolean().optional().describe('Also return a scaled JPEG of the screen.'), marks: z.boolean().optional().describe('With screenshot: outline interactive nodes and label them with @refs.') },
  }, async args => bridgeImageCall(bridge, 'screen.snapshot', args));
  mcp.registerTool('take_screenshot', {
    description: 'JPEG screenshot of the phone screen, scaled down (default max 800px wide). marks:true returns a Set-of-Mark image (interactive nodes outlined and labelled @ref) plus the matching lines, so a visual decision can be acted on by ref without a second call. FLAG_SECURE apps return black.',
    inputSchema: {
      maxWidth: z.number().int().min(100).max(2000).optional().describe('Default 800.'),
      quality: z.number().int().min(10).max(100).optional().describe('JPEG quality, default 70.'),
      marks: z.boolean().optional(), maxNodes: z.number().int().min(1).max(200).optional().describe('With marks: how many interactive nodes to label, default 60.'),
    },
  }, async args => bridgeImageCall(bridge, 'screen.screenshot', args));
  mcp.registerTool('read_text', {
    description: 'Full visible text under an @ref (or the whole screen without ref), untruncated and in reading order, one node per line. For articles, messages, long lists; snapshot lines cap text at 300 chars.',
    inputSchema: { ref: refSchema.optional(), maxChars: z.number().int().min(500).max(200000).optional().describe('Default 50000.'), offset: z.number().int().nonnegative().optional() },
  }, async args => bridgeCall(bridge, 'screen.readText', args));
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

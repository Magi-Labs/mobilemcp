import type { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js';
import type { Bridge } from '../bridge.js';
import { z } from 'zod';
import { actionObservationSchema, bridgeCall } from './helpers.js';
export function registerNotificationTools(mcp: McpServer, bridge: Bridge): void {
  mcp.registerTool('get_notifications', {
    description: 'Active notifications (newest first): key, app, title, text, time, ongoing flag and available actions (with whether an action accepts a reply). Needs notification access granted in the app. Filter by package or text.',
    inputSchema: { package: z.string().max(200).optional(), query: z.string().max(200).optional(), limit: z.number().int().min(1).max(100).optional().describe('Default 30.'), includeOngoing: z.boolean().optional().describe('Include persistent/ongoing notifications; default false.') },
  }, async args => bridgeCall(bridge, 'notifications.list', args));
  mcp.registerTool('open_notification', {
    description: 'Open a notification (its content intent), like tapping it in the shade.',
    inputSchema: { key: z.string().max(500), ...actionObservationSchema },
  }, async args => bridgeCall(bridge, 'notifications.open', args));
  mcp.registerTool('notification_action', {
    description: 'Trigger a notification action by index or title, e.g. "Mark as read", "Reply". For reply-capable actions pass text to send a direct reply without opening the app.',
    inputSchema: { key: z.string().max(500), action: z.union([z.number().int().nonnegative(), z.string().max(100)]), text: z.string().max(5000).optional() },
  }, async args => bridgeCall(bridge, 'notifications.act', args));
  mcp.registerTool('dismiss_notification', {
    description: 'Dismiss one notification by key, or all dismissable ones with all:true.',
    inputSchema: { key: z.string().max(500).optional(), all: z.boolean().optional() },
  }, async args => bridgeCall(bridge, 'notifications.dismiss', args));
}

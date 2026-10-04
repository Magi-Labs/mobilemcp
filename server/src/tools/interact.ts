import type { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js';
import type { Bridge } from '../bridge.js';
import { z } from 'zod';
import { refSchema, actionObservationSchema, bridgeCall } from './helpers.js';
export const KEYS = ['back', 'home', 'recents', 'notifications', 'quick_settings', 'power', 'lock', 'enter', 'dismiss_notifications'] as const;
export function registerInteractTools(mcp: McpServer, bridge: Bridge): void {
  mcp.registerTool('tap', {
    description: 'Tap an @ref (preferred) or screen coordinates. Uses the accessibility click of the node or its nearest clickable ancestor, otherwise a real touch at its center. longPress:true for long press.',
    inputSchema: {
      ref: refSchema.optional(),
      x: z.number().int().nonnegative().optional().describe('Screen x in pixels; requires y. Fallback when no node exists.'),
      y: z.number().int().nonnegative().optional(),
      longPress: z.boolean().optional(),
      ...actionObservationSchema,
    },
  }, async args => bridgeCall(bridge, 'interact.tap', args));
  mcp.registerTool('type_text', {
    description: 'Type into an editable @ref or the focused field. clear:true replaces existing text; submit:true presses the keyboard action (enter/send/search) afterwards.',
    inputSchema: {
      text: z.string().max(10000),
      ref: refSchema.optional(),
      clear: z.boolean().optional().describe('Replace existing text. Default false (append).'),
      submit: z.boolean().optional(),
      ...actionObservationSchema,
    },
  }, async args => bridgeCall(bridge, 'interact.type', args));
  mcp.registerTool('scroll', {
    description: 'Scroll content inside a scrollable @ref (or the main scrollable on screen) by whole pages. direction is where content should reveal: down shows content below.',
    inputSchema: {
      direction: z.enum(['down', 'up', 'left', 'right']),
      ref: refSchema.optional(),
      amount: z.number().int().min(1).max(10).optional().describe('Pages, default 1.'),
      ...actionObservationSchema,
    },
  }, async args => bridgeCall(bridge, 'interact.scroll', args));
  mcp.registerTool('swipe', {
    description: 'Raw finger swipe. direction is the finger movement (swipe up = finger moves up = content moves up). Centered on the @ref or screen, or explicit from/to coordinates. Use for pagers, dismiss, pull-to-refresh, drawers.',
    inputSchema: {
      direction: z.enum(['up', 'down', 'left', 'right']).optional(),
      ref: refSchema.optional(),
      distance: z.number().int().min(50).max(3000).optional().describe('Pixels, default 60% of the screen along that axis.'),
      fromX: z.number().int().optional(), fromY: z.number().int().optional(), toX: z.number().int().optional(), toY: z.number().int().optional(),
      duration: z.number().int().min(50).max(5000).optional().describe('Milliseconds, default 300.'),
      ...actionObservationSchema,
    },
  }, async args => bridgeCall(bridge, 'interact.swipe', args));
  mcp.registerTool('drag', {
    description: 'Long-press then drag: hold on the source (@ref or x,y) for holdMs, move to the target (@ref or x,y) over moveMs, release. Use for rearranging icons, creating launcher folders, sliders, drag-and-drop. The target of a launcher drop is the icon to merge with or an empty cell.',
    inputSchema: {
      fromRef: refSchema.optional(), fromX: z.number().int().optional(), fromY: z.number().int().optional(),
      toRef: refSchema.optional(), toX: z.number().int().optional(), toY: z.number().int().optional(),
      holdMs: z.number().int().min(100).max(5000).optional().describe('Long-press duration before moving, default 700.'),
      moveMs: z.number().int().min(100).max(5000).optional().describe('Movement duration, default 600.'),
      hoverMs: z.number().int().min(0).max(5000).optional().describe('Pause over the target before releasing (launchers need ~400ms to open a folder/merge), default 500.'),
      ...actionObservationSchema,
    },
  }, async args => bridgeCall(bridge, 'interact.drag', args));
  mcp.registerTool('press_key', {
    description: 'System keys: back, home, recents, notifications, quick_settings, power (dialog), lock, dismiss_notifications, enter (keyboard action on the focused field).',
    inputSchema: { key: z.enum(KEYS), ...actionObservationSchema },
  }, async args => bridgeCall(bridge, 'interact.key', args));
  mcp.registerTool('set_clipboard', {
    description: 'Put text on the phone clipboard, e.g. to paste into a field that rejects accessibility text setting.',
    inputSchema: { text: z.string().max(100000) },
  }, async args => bridgeCall(bridge, 'interact.setClipboard', args));
}

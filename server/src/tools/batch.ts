import type { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js';
import type { Bridge } from '../bridge.js';
import { z } from 'zod';
import { refSchema, actionObservationSchema, bridgeCall } from './helpers.js';
import { KEYS } from './interact.js';
const step = z.discriminatedUnion('action', [
  z.object({ action: z.literal('tap'), ref: refSchema.optional(), x: z.number().int().optional(), y: z.number().int().optional(), longPress: z.boolean().optional() }),
  z.object({ action: z.literal('type'), text: z.string().max(10000), ref: refSchema.optional(), clear: z.boolean().optional(), submit: z.boolean().optional() }),
  z.object({ action: z.literal('scroll'), direction: z.enum(['down', 'up', 'left', 'right']), ref: refSchema.optional(), amount: z.number().int().min(1).max(10).optional() }),
  z.object({ action: z.literal('swipe'), direction: z.enum(['up', 'down', 'left', 'right']).optional(), ref: refSchema.optional(), distance: z.number().int().optional(), fromX: z.number().int().optional(), fromY: z.number().int().optional(), toX: z.number().int().optional(), toY: z.number().int().optional(), duration: z.number().int().optional() }),
  z.object({ action: z.literal('drag'), fromRef: refSchema.optional(), fromX: z.number().int().optional(), fromY: z.number().int().optional(), toRef: refSchema.optional(), toX: z.number().int().optional(), toY: z.number().int().optional(), holdMs: z.number().int().optional(), moveMs: z.number().int().optional(), hoverMs: z.number().int().optional() }),
  z.object({ action: z.literal('key'), key: z.enum(KEYS) }),
  z.object({ action: z.literal('openApp'), app: z.string().min(1).max(200) }),
  z.object({ action: z.literal('openUrl'), url: z.string().min(1).max(4000) }),
  z.object({ action: z.literal('wait'), ms: z.number().int().min(50).max(30000) }),
  z.object({ action: z.literal('waitFor'), text: z.string().max(500).optional(), package: z.string().max(200).optional(), gone: z.boolean().optional(), timeout: z.number().int().min(1000).max(60000).optional() }),
  z.object({ action: z.literal('snapshot'), query: z.string().max(200).optional(), scope: refSchema.optional(), interactiveOnly: z.boolean().optional(), maxNodes: z.number().int().min(1).max(500).optional(), maxChars: z.number().int().min(500).max(50000).optional() }),
]);
export function registerBatchTools(mcp: McpServer, bridge: Bridge): void {
  mcp.registerTool('run_mobile_actions', {
    description: 'Run 1-20 known steps sequentially on the phone in one call (tap, type, scroll, swipe, drag, key, openApp, openUrl, wait, waitFor, snapshot). Stops on the first error and reports completed steps; no rollback. One observation is returned at the end unless observe:false. Refs resolved per step.',
    inputSchema: {
      steps: z.array(step).min(1).max(20),
      timeout: z.number().int().min(1000).max(60000).optional().describe('Total budget in ms, default 60000.'),
      ...actionObservationSchema,
    },
  }, async args => bridgeCall(bridge, 'device.batch', args));
}

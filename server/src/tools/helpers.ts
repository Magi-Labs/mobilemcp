import type { Bridge } from '../bridge.js';
import type { BridgeAction } from '@mobilemcp/shared';
import type { CallToolResult } from '@modelcontextprotocol/sdk/types.js';
import { z } from 'zod';
import { errText, okJson, imageResult } from '../toolResult.js';
export const refSchema = z.string().regex(/^@?\d+$/).describe('An @ref from get_screen_snapshot, e.g. "@12".');
export const observationSchema = {
  query: z.string().max(200).optional().describe('Keep only nodes whose text, description or id contains this substring (case-insensitive).'),
  scope: refSchema.optional().describe('Limit to the subtree under this @ref.'),
  interactiveOnly: z.boolean().optional().describe('Skip plain text/image nodes; keep clickable, editable, checkable, scrollable ones.'),
  maxNodes: z.number().int().min(1).max(500).optional().describe('Default 120.'),
  maxChars: z.number().int().min(500).max(50000).optional().describe('Default 12000 characters of node text.'),
  offset: z.number().int().nonnegative().optional().describe('Continue from nextOffset of a truncated snapshot.'),
  since: z.string().optional().describe('Version of a retained snapshot. Returns changed/removed nodes, or a full reset if the baseline is gone.'),
};
export const actionObservationSchema = {
  observe: z.boolean().optional().describe('Return compact screen state after acting; default true (30 nodes, 1500 chars).'),
  observationScope: refSchema.optional().describe('Scope the returned state to a subtree, e.g. a dialog or list.'),
  maxNodes: z.number().int().min(1).max(500).optional(),
  maxChars: z.number().int().min(500).max(50000).optional(),
};
export async function bridgeCall(bridge: Bridge, action: BridgeAction, params: Record<string, unknown> = {}): Promise<CallToolResult> {
  try { return okJson(await bridge.request(action, params)); }
  catch (e) { return errText(e instanceof Error ? e.message : String(e)); }
}
export async function bridgeImageCall(bridge: Bridge, action: BridgeAction, params: Record<string, unknown> = {}): Promise<CallToolResult> {
  try { return imageResult(await bridge.request(action, params)); }
  catch (e) { return errText(e instanceof Error ? e.message : String(e)); }
}

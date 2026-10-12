import type { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js';
import type { Bridge } from '../bridge.js';
import { z } from 'zod';
import { bridgeCall } from './helpers.js';
export function registerShellTools(mcp: McpServer, bridge: Bridge) {
 mcp.registerTool('get_command_access', {description:'Read phone-owned privileged command toggle and Shizuku availability. Agents cannot enable permission.',inputSchema:{}}, async () => bridgeCall(bridge,'shell.status',{}));
 mcp.registerTool('run_phone_command', {description:'Execute argv directly with Shizuku shell/root privileges, without opening an app. Requires phone toggle and Shizuku grant. No implicit shell; use sh -c only intentionally. Can modify apps/system/data or display even in Background only. Only execute user-authorized work. Timeout/disconnection means unknown effects; never automatically replay a mutation. Output is bounded to 64 KiB; no output stored by the hub.',inputSchema:{argv:z.array(z.string().max(4096)).min(1).max(64),timeout:z.number().int().min(1000).max(30000).optional()}},async args=>bridgeCall(bridge,'shell.run',args));
}

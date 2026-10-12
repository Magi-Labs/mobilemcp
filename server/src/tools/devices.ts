import type { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js';
import type { Bridge } from '../bridge.js';
import { z } from 'zod';
import { errText, okJson } from '../toolResult.js';
import { bridgeCall } from './helpers.js';
export function registerDeviceTools(mcp: McpServer, bridge: Bridge): void {
  mcp.registerTool('list_devices', {
    description: 'List phones connected to the hub and which one this session uses. A single phone is selected automatically.',
    inputSchema: {},
  }, async () => { try { return okJson(await bridge.listDevices()); } catch (e) { return errText(String(e)); } });
  mcp.registerTool('select_device', {
    description: 'Select the phone this session controls. Refs from another device are invalid afterwards.',
    inputSchema: { deviceId: z.string().min(8).max(100) },
  }, async ({ deviceId }) => { try { return okJson(await bridge.selectDevice(deviceId)); } catch (e) { return errText(String(e)); } });
  mcp.registerTool('get_background_status', {
    description: 'Read battery, lock state and background mode without inspecting or waking the screen. Uses a separate read queue so UI waits do not delay it.',
    inputSchema: {},
  }, async () => bridgeCall(bridge, 'device.status'));
  mcp.registerTool('get_execution_policy', {
    description: 'Read the phone-owned Background only policy and allowed bridge actions. Change the mode locally in the phone app; agents cannot override it.',
    inputSchema: {},
  }, async () => bridgeCall(bridge, 'device.executionPolicy'));
  mcp.registerTool('get_device_info', {
    description: 'Phone details in screen mode. In Background only mode returns compact battery/lock status without inspecting the foreground app.',
    inputSchema: {},
  }, async () => bridgeCall(bridge, 'device.info'));
}

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
  mcp.registerTool('get_device_info', {
    description: 'Phone model, Android version, screen size/orientation, battery, keyboard visibility and the foreground app/activity.',
    inputSchema: {},
  }, async () => bridgeCall(bridge, 'device.info'));
}

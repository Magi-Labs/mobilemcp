import type { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js';
import type { Bridge } from '../bridge.js';
import { z } from 'zod';
import { actionObservationSchema, bridgeCall } from './helpers.js';
export const SETTINGS_PAGES = ['main', 'wifi', 'bluetooth', 'internet_panel', 'wifi_panel', 'volume_panel', 'display', 'sound', 'notifications', 'apps', 'app_info', 'app_notifications', 'accessibility', 'date', 'location', 'security', 'battery', 'storage', 'airplane', 'mobile_data', 'developer', 'home', 'lock_screen', 'language', 'input_method', 'nfc', 'vpn', 'print', 'about'] as const;
export function registerSystemTools(mcp: McpServer, bridge: Bridge): void {
  mcp.registerTool('open_settings', {
    description: 'Open a system Settings page or quick panel. app_info/app_notifications need package. Use this instead of navigating Settings manually.',
    inputSchema: { page: z.enum(SETTINGS_PAGES), package: z.string().max(200).optional(), ...actionObservationSchema },
  }, async args => bridgeCall(bridge, 'system.openSettings', args));
  mcp.registerTool('start_intent', {
    description: 'Start any Android activity intent: action (e.g. android.intent.action.SENDTO), data URI, package/component, MIME type, categories, extras (string/number/boolean). Dialer: action=android.intent.action.DIAL data=tel:+1... SMS: action=android.intent.action.SENDTO data=smsto:+1... extras={sms_body:"hi"}.',
    inputSchema: {
      action: z.string().max(200), data: z.string().max(4000).optional(), package: z.string().max(200).optional(), component: z.string().max(300).optional().describe('package/ClassName'),
      type: z.string().max(100).optional(), categories: z.array(z.string().max(200)).max(5).optional(), extras: z.record(z.union([z.string().max(10000), z.number(), z.boolean()])).optional(),
      ...actionObservationSchema,
    },
  }, async args => bridgeCall(bridge, 'system.startIntent', args));
  mcp.registerTool('uninstall_app', {
    description: 'Open the system uninstall confirmation for a package (the user or agent confirms on screen).',
    inputSchema: { package: z.string().min(1).max(200), ...actionObservationSchema },
  }, async args => bridgeCall(bridge, 'apps.uninstall', args));
  mcp.registerTool('media_control', {
    description: 'Send a media key: play, pause, play_pause, next, previous, stop. Works for the active media session (Spotify, YouTube, ...).',
    inputSchema: { command: z.enum(['play', 'pause', 'play_pause', 'next', 'previous', 'stop']) },
  }, async args => bridgeCall(bridge, 'system.media', args));
  mcp.registerTool('set_volume', {
    description: 'Set or read volume. level 0-100 or direction up/down/mute/unmute for a stream (music default, ring, alarm, notification, call). Without level/direction it reports current levels.',
    inputSchema: { stream: z.enum(['music', 'ring', 'alarm', 'notification', 'call', 'system']).optional(), level: z.number().int().min(0).max(100).optional(), direction: z.enum(['up', 'down', 'mute', 'unmute']).optional() },
  }, async args => bridgeCall(bridge, 'system.volume', args));
  mcp.registerTool('set_brightness', {
    description: 'Screen brightness 0-100 and/or auto mode. Needs the "Modify system settings" permission granted in the app.',
    inputSchema: { level: z.number().int().min(0).max(100).optional(), auto: z.boolean().optional() },
  }, async args => bridgeCall(bridge, 'system.brightness', args));
  mcp.registerTool('set_rotation', {
    description: 'Rotation: auto, portrait or landscape. Needs the "Modify system settings" permission.',
    inputSchema: { mode: z.enum(['auto', 'portrait', 'landscape']) },
  }, async args => bridgeCall(bridge, 'system.rotation', args));
  mcp.registerTool('set_dnd', {
    description: 'Do Not Disturb: off, priority, alarms_only, total_silence. Needs notification access granted in the app.',
    inputSchema: { mode: z.enum(['off', 'priority', 'alarms_only', 'total_silence']) },
  }, async args => bridgeCall(bridge, 'system.dnd', args));
  mcp.registerTool('set_flashlight', { description: 'Turn the back camera torch on or off.', inputSchema: { on: z.boolean() } }, async args => bridgeCall(bridge, 'system.flashlight', args));
  mcp.registerTool('wake_screen', {
    description: 'Turn the screen on (and keep it on briefly). Does not unlock a secured lock screen; swipe up afterwards for an unsecured one.',
    inputSchema: { ...actionObservationSchema },
  }, async args => bridgeCall(bridge, 'system.wake', args));
}

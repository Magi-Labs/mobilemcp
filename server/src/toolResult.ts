import type { CallToolResult } from '@modelcontextprotocol/sdk/types.js';
export function okText(text: string): CallToolResult { return { content: [{ type: 'text', text }] }; }
export function okJson(data: unknown): CallToolResult {
  const failed = !!data && typeof data === 'object' && 'error' in data;
  return { ...okText(typeof data === 'string' ? data : JSON.stringify(data)), ...(failed ? { isError: true } : {}) };
}
export function errText(message: string): CallToolResult {
  const code = /\b([A-Z][A-Z_]{2,}):/.exec(message)?.[1];
  return { ...okText(message), isError: true, ...(code ? { structuredContent: { error: message, code } } : {}) };
}
/** Lifts a `screenshot` data URL out of a result into a real MCP image block. */
export function imageResult(data: any): CallToolResult {
  if (!data || typeof data !== 'object') return okJson(data);
  const { screenshot, ...metadata } = data;
  if (!screenshot) return okJson(metadata);
  const match = /^data:(image\/(?:png|jpeg|webp));base64,([A-Za-z0-9+/=]+)$/.exec(screenshot);
  if (!match) return errText('Invalid screenshot data');
  return { content: [{ type: 'image', data: match[2], mimeType: match[1] }, { type: 'text', text: JSON.stringify(metadata) }] };
}

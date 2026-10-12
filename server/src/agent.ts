import { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js';
import { StdioServerTransport } from '@modelcontextprotocol/sdk/server/stdio.js';
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { z } from 'zod';
import { okJson, okText, errText } from './toolResult.js';
const script = process.env.MOBILE_AGENT_SCRIPT ?? fileURLToPath(new URL('../../tools/phone-jobs.py', import.meta.url));
const mcp = new McpServer({ name: 'mobile-agent', version: '0.6.0' });
function run(command: string, id?: string, input?: unknown, target = script): Promise<any> {
  return new Promise((resolve, reject) => {
    const child = spawn(process.env.PYTHON ?? 'python3', [target, command, ...(id ? [id] : [])], { stdio: ['pipe', 'pipe', 'pipe'], env: process.env });
    let output = ''; let error = '';
    child.stdout.on('data', chunk => { output += chunk; if (output.length > 2_000_000) child.kill(); });
    child.stderr.on('data', chunk => { error = (error + chunk).slice(-2000); });
    child.on('error', reject);
    child.on('close', code => {
      try { if (code) { let message = error || 'Command helper failed'; try { message = JSON.parse(output).error ?? message; } catch {} reject(new Error(message.trim())); } else resolve(JSON.parse(output)); }
      catch (e) { reject(e); }
    });
    child.stdin.end(input ? JSON.stringify(input) : '');
  });
}
const invoke = async (command: string, id?: string, input?: unknown) => { try { return okText(JSON.stringify(await run(command, id, input))); } catch(e) { return errText(String(e)); } };
mcp.registerTool('submit_background_job', {
  description: 'Queue a durable read-only phone job. Returns immediately with an ID. Supports status, app lists/context, and scoped notification reads. Pins the selected device; never operates the screen or extracts OTPs. Reuse an idempotencyKey for retries.',
  inputSchema: { action: z.enum(['status','apps','context','notifications']), params: z.record(z.unknown()).optional(), deviceName: z.string().optional(), idempotencyKey: z.string().max(200).optional() },
}, args => invoke('submit', undefined, args));
for (const [name, command] of [['get_background_job','get'],['cancel_background_job','cancel']] as const) {
  mcp.registerTool(name, { description: command === 'get' ? 'Read persisted job status and result; unknown outcomes are not retried automatically.' : 'Cancel a queued job. Running jobs cannot be undone.', inputSchema: { id: z.string().regex(/^[a-f0-9]{32}$/) } }, args => invoke(command, args.id));
}
mcp.registerTool('list_background_jobs', { description: 'List the latest 50 jobs, including completed, failed and unknown outcomes. Results expire after 24 hours when the worker runs.', inputSchema: {} }, () => invoke('list'));
mcp.registerTool('resume_background_jobs', { description: 'Start the queue worker after a restart. Previously running jobs become unknown rather than being replayed.', inputSchema: {} }, () => invoke('resume'));
const appsScript = process.env.MOBILE_APPS_SCRIPT ?? fileURLToPath(new URL('../../tools/phone-apps.py', import.meta.url));
for (const [name, command] of [['get_app_context','context'],['list_app_shortcuts','shortcuts']] as const) {
  mcp.registerTool(name, { description: 'Read version-aware app context or known shortcuts without opening the app. Ambiguous app names must be resolved using an exact package.', inputSchema: { app: z.string().min(1).max(200) } }, async args => { try { return okJson(await run(command,args.app,undefined,appsScript)); } catch(e) { return errText(String(e)); } });
}
const otpScript = process.env.MOBILE_OTP_SCRIPT ?? fileURLToPath(new URL('../../tools/phone-otp.py', import.meta.url));
const otpScope = { source: z.enum(['notification','email']), purpose: z.enum(['sign_in','verify_contact','complete_authorized_task']), task: z.string().min(2).max(200), service: z.string().min(2).max(200), sender: z.string().min(2).max(200), since: z.number(), package: z.string().optional() };
for (const [name, command] of [['check_otp_permission','permission'],['read_notification_otp','read']] as const) {
  mcp.registerTool(name, { description: 'Check the owner OTP grant before reading. Only for a task already authorized by the user. Use exact expected sender, service and challenge timestamp. When allowed, continue without another approval prompt. Notification reads return only a fresh unambiguous code, never save message bodies or codes. This authorizes retrieval, not a new transaction or account change.', inputSchema: otpScope }, args => invokeOtp(command,args));
}
async function invokeOtp(command: string, args: unknown) { try { return okText(JSON.stringify(await run(command,undefined,args,otpScript))); } catch(e) { return errText(String(e)); } }
mcp.registerTool('extract_email_otp', { description: 'After check_otp_permission allows email, use the connected mail tools to search/read only the expected sender and service since the challenge. Pass those message bodies here for freshness and ambiguity checks. Returns a code without persisting it. The local worker cannot directly call cloud mail connectors.', inputSchema: { ...otpScope, messages: z.array(z.object({ sender:z.string(),time:z.number(),text:z.string().max(20000) })).max(20) } }, args => invokeOtp('email',args));
const backgroundScript = process.env.MOBILE_BACKGROUND_SCRIPT ?? fileURLToPath(new URL('../../tools/phone-background.py', import.meta.url));
mcp.registerTool('get_command_access', { description:'Read phone command toggle and Shizuku permission. Read-only; cannot grant access.', inputSchema:{} }, async () => { try { return okJson(await run('command-access',undefined,undefined,backgroundScript)); } catch(e) { return errText(String(e)); } });
mcp.registerTool('run_phone_command', { description:'Run argv using phone Shizuku shell/root privileges. Requires phone-owned toggle and Shizuku grant. No implicit shell. Commands may change packages, data or the display even in Background only. Only user-authorized work. A timeout or disconnect has unknown effects; never replay mutations automatically. Not stored in the durable job queue.', inputSchema:{argv:z.array(z.string().max(4096)).min(1).max(64),timeout:z.number().int().min(1000).max(30000).optional()} }, async args => { try { return okJson(await run('run-command',undefined,args,backgroundScript)); } catch(e) { return errText(String(e)); } });
await mcp.connect(new StdioServerTransport());

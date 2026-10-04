import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { StreamableHTTPClientTransport } from '@modelcontextprotocol/sdk/client/streamableHttp.js';
import { writeFileSync } from 'node:fs';
const c = new Client({ name: 'shot', version: '0' });
await c.connect(new StreamableHTTPClientTransport(new URL(process.env.MOBILEMCP_URL), { requestInit: { headers: { Authorization: `Bearer ${process.env.MOBILEMCP_TOKEN}` } } }));
const r = await c.callTool({ name: 'take_screenshot', arguments: { maxWidth: Number(process.env.W ?? 540), quality: 70 } });
const img = r.content.find(x => x.type === 'image'); writeFileSync(process.env.OUT, Buffer.from(img.data, 'base64'));
console.log(r.content.find(x => x.type === 'text')?.text); await c.close();

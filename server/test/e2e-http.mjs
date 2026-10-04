import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { StreamableHTTPClientTransport } from '@modelcontextprotocol/sdk/client/streamableHttp.js';
const url = process.env.MOBILEMCP_URL, token = process.env.MOBILEMCP_TOKEN;
const client = new Client({ name: 'e2e-http', version: '0' });
await client.connect(new StreamableHTTPClientTransport(new URL(url), { requestInit: { headers: { Authorization: `Bearer ${token}` } } }));
for (const [name, args = {}] of JSON.parse(process.env.STEPS)) {
  const t = Date.now(); const r = await client.callTool({ name, arguments: args });
  const text = r.content.filter(c => c.type === 'text').map(c => c.text).join('\n');
  console.log(`\n=== ${name} ${JSON.stringify(args)} [${Date.now() - t}ms${r.isError ? ', ERROR' : ''}]\n${text.slice(0, Number(process.env.MAX_CHARS ?? 1200))}`);
}
await client.close();

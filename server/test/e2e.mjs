import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { StdioClientTransport } from '@modelcontextprotocol/sdk/client/stdio.js';
import { writeFileSync } from 'node:fs';
const client = new Client({ name: 'e2e', version: '0' });
await client.connect(new StdioClientTransport({ command: 'node', args: [process.env.HOME + '/projects/mobilemcp/server/dist/index.js'] }));
const call = async (name, args = {}) => {
  const t = Date.now(); const r = await client.callTool({ name, arguments: args });
  const text = r.content.filter(c => c.type === 'text').map(c => c.text).join('\n');
  const img = r.content.find(c => c.type === 'image');
  if (img) writeFileSync(`${process.env.SCRATCH}/${name}.jpg`, Buffer.from(img.data, 'base64'));
  console.log(`\n=== ${name} ${JSON.stringify(args)} [${Date.now() - t}ms, ${text.length} chars${img ? ', +image' : ''}${r.isError ? ', ERROR' : ''}]`);
  console.log(text.length > 2500 ? text.slice(0, 2500) + ' …' : text);
  return text;
};
const tools = await client.listTools(); console.log('tools:', tools.tools.map(t => t.name).join(', '));
for (const step of JSON.parse(process.env.STEPS)) await call(step[0], step[1] ?? {});
await client.close();

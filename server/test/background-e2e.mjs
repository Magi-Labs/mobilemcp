import assert from 'node:assert/strict';
import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { StreamableHTTPClientTransport } from '@modelcontextprotocol/sdk/client/streamableHttp.js';
const client = new Client({name:'background-e2e',version:'1'});
await client.connect(new StreamableHTTPClientTransport(new URL(process.env.MOBILEMCP_URL), {requestInit:{headers:{Authorization:`Bearer ${process.env.MOBILEMCP_TOKEN}`}}}));
const call = (name,args={}) => client.callTool({name,arguments:args});
const body = r => { assert.ok(!r.isError, r.content.find(c=>c.type==='text')?.text); return JSON.parse(r.content.find(c=>c.type==='text').text); };
try {
 const policy=body(await call('get_execution_policy'));
 if (process.argv.includes('--background')) {
  assert.equal(policy.backgroundOnly,true);
  const before=body(await call('get_background_status'));
  if (process.argv.includes("--locked")) assert.equal(before.locked,true);
  const blocked=await call('open_app',{app:'com.android.settings'});
  assert.equal(blocked.isError,true);
  assert.match(blocked.content.find(c=>c.type==='text').text,/BACKGROUND_ONLY/);
  const snapshot=await call('get_screen_snapshot',{maxNodes:1});
  assert.equal(snapshot.isError,true);
  const after=body(await call('get_background_status'));
  assert.equal(after.interactive,before.interactive);
  console.log(JSON.stringify({backgroundGuard:'passed',screenReadGuard:'passed',statusAvailable:true,statusBytes:JSON.stringify(after).length}));
 } else {
  assert.equal(policy.backgroundOnly,false);
  const began=performance.now();
  const waiting=call('wait_for_text',{text:'MOBILEMCP_E2E_TEXT_NOT_ON_SCREEN_7fda',timeout:3000}).then(()=>performance.now()-began);
  await new Promise(resolve=>setTimeout(resolve,150));
  const readStart=performance.now();
  const status=await call('get_background_status');
  assert.equal(status.isError,undefined);
  const statusMs=performance.now()-readStart;
  const waitMs=await waiting;
  assert.ok(statusMs<waitMs-500,`status was held behind UI wait: status=${statusMs} wait=${waitMs}`);
  console.log(JSON.stringify({concurrentRead:'passed',statusMs:Math.round(statusMs),uiWaitMs:Math.round(waitMs),statusBytes:JSON.stringify(body(status)).length}));
 }
} finally {await client.close();}

import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { StdioClientTransport } from '@modelcontextprotocol/sdk/client/stdio.js';
import { createServer } from 'node:http';
import { resolve } from 'node:path';
import assert from 'node:assert/strict';
let allowed=false;let calls=0;
const hub=createServer(async(req,res)=>{let body='';for await(const c of req)body+=c;const r=JSON.parse(body);let answer;
 if(r.action==='hub.listDevices')answer={result:{devices:[{name:'Preview',id:'fixture'}]}};
 else if(r.action==='shell.status')answer={result:{enabled:allowed,shizukuRunning:true,shizukuGranted:true}};
 else if(r.action==='shell.run'){calls++;assert.equal(r.deviceId,'fixture');assert.deepEqual(r.params.argv,['echo','literal; value']);answer=allowed?{result:{uid:2000,exitCode:null,timedOut:true,outcome:'unknown',output:''}}:{error:'COMMAND_PERMISSION_REQUIRED: enable on phone'};}
 else throw Error('Unexpected action');res.writeHead(200,{'content-type':'application/json'});res.end(JSON.stringify(answer));});
await new Promise(r=>hub.listen(0,'127.0.0.1',r));
const client=new Client({name:'shell-fixture',version:'1'});
const transport=new StdioClientTransport({command:process.execPath,args:[resolve('server/dist/agent.js')],env:{...process.env,MOBILE_BACKGROUND_SCRIPT:resolve('tools/phone-background.py'),MOBILEMCP_URL:`http://127.0.0.1:${hub.address().port}/mcp`,MOBILEMCP_TOKEN:'fixture-only'}});
try {
 await client.connect(transport);
 const status=await client.callTool({name:'get_command_access',arguments:{}});assert.equal(JSON.parse(status.content[0].text).enabled,false);
 const args={argv:['echo','literal; value'],timeout:1000};
 const denied=await client.callTool({name:'run_phone_command',arguments:args});assert.equal(denied.isError,true);assert.match(denied.content[0].text,/COMMAND_PERMISSION_REQUIRED/);
 allowed=true;
 const result=await client.callTool({name:'run_phone_command',arguments:args});assert.equal(JSON.parse(result.content[0].text).outcome,'unknown');assert.equal(calls,2);
 console.log(JSON.stringify({status:true,permissionErrorPreserved:true,argvPreserved:true,unknownOutcomePreserved:true,noAutomaticReplay:true}));
} finally {await client.close();await new Promise(r=>hub.close(r));}

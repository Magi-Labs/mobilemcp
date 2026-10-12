import http.server,threading,tempfile,subprocess,os,json,time,sqlite3
from pathlib import Path
script=str(Path(__file__).resolve().parents[1]/'phone-jobs.py')
class Handler(http.server.BaseHTTPRequestHandler):
 def do_POST(self):
  b=json.loads(self.rfile.read(int(self.headers['Content-Length'])))
  result={'devices':[{'name':'Preview','id':'test-device'}]} if b['action']=='hub.listDevices' else {'backgroundOnly':True,'device':b['deviceId']}
  self.send_response(200);self.end_headers();self.wfile.write(json.dumps({'result':result}).encode())
 def log_message(self,*args):pass
srv=http.server.HTTPServer(('127.0.0.1',0),Handler);threading.Thread(target=srv.serve_forever,daemon=True).start()
with tempfile.TemporaryDirectory() as d:
 env={**os.environ,'MOBILE_AGENT_DB':d+'/jobs.sqlite','MOBILEMCP_URL':f'http://127.0.0.1:{srv.server_port}/mcp','MOBILEMCP_TOKEN':'test','MOBILE_AGENT_AUTOSTART':'0'}
 def call(cmd,id=None,body=None,ok=True):
  p=subprocess.run(['python3',script,cmd]+([id] if id else []),input=json.dumps(body) if body else '',text=True,capture_output=True,env=env)
  assert (p.returncode==0)==ok,p.stdout+p.stderr
  return json.loads(p.stdout)
 a=call('submit',body={'action':'status','idempotencyKey':'same'})
 assert call('submit',body={'action':'status','idempotencyKey':'same'})['id']==a['id']
 call('submit',body={'action':'apps','idempotencyKey':'same'},ok=False)
 b=call('submit',body={'action':'apps'});assert call('cancel',b['id'])['status']=='cancelled'
 call('submit',body={'action':'notifications'},ok=False)
 call('submit',body={'action':'status','params':[]},ok=False)
 db=sqlite3.connect(env['MOBILE_AGENT_DB']);db.execute("UPDATE jobs SET status='running' WHERE id=?",(a['id'],));db.commit()
 c=call('submit',body={'action':'status'})
 worker=subprocess.Popen(['python3',script,'worker'],env=env,stdout=subprocess.DEVNULL)
 try:
  for _ in range(50):
   result=call('get',c['id'])
   if result['status']=='complete':break
   time.sleep(.1)
  assert result['status']=='complete' and result['result']['device']=='test-device'
  assert call('get',a['id'])['status']=='unknown'
 finally:worker.terminate();worker.wait()
srv.shutdown()
print('PASS: idempotency, conflict, cancellation, scope, parameter validation, pinned device, completion, interrupted recovery')

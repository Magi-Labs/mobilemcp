import unittest,tempfile,threading,json,os,subprocess,shutil
from pathlib import Path
from http.server import BaseHTTPRequestHandler,HTTPServer
SCRIPT=Path(__file__).resolve().parents[1]/'phone-apps.py'
class RegistryTests(unittest.TestCase):
 def setUp(self):
  self.tmp=tempfile.TemporaryDirectory();self.root=Path(self.tmp.name);shutil.copy2(SCRIPT,self.root/'phone-apps.py');self.fingerprint='1:100';self.calls=[]
  owner=self
  class Handler(BaseHTTPRequestHandler):
   def log_message(self,*a):pass
   def do_POST(self):
    req=json.loads(self.rfile.read(int(self.headers['Content-Length'])));owner.calls.append(req['action'])
    profile={'package':'test.swiggy','label':'Swiggy','versionName':'1','fingerprint':owner.fingerprint,'shortcuts':[]}
    result={'hub.listDevices':{'devices':[{'name':'Preview','id':'test-device'}]},'apps.list':{'total':1,'revision':1,'profiles':[profile]},'device.info':{'backgroundOnly':True,'locked':False},'notifications.list':{'notifications':[]}}[req['action']]
    body=json.dumps({'result':result}).encode();self.send_response(200);self.end_headers();self.wfile.write(body)
  self.server=HTTPServer(('127.0.0.1',0),Handler);self.thread=threading.Thread(target=self.server.serve_forever,daemon=True);self.thread.start()
 def tearDown(self):self.server.shutdown();self.server.server_close();self.tmp.cleanup()
 def run_cli(self,*args):
  env=dict(os.environ,MOBILEMCP_URL=f'http://127.0.0.1:{self.server.server_port}/mcp',MOBILEMCP_TOKEN='test-token-not-a-secret')
  return subprocess.run(['python3',str(self.root/'phone-apps.py'),*args],env=env,capture_output=True,text=True)
 def test_background_shortcut_never_opens_app(self):
  r=self.run_cli('run','swiggy','notifications');self.assertEqual(r.returncode,0,r.stderr);self.assertNotIn('apps.open',self.calls)
 def test_changed_version_invalidates_verified_flow(self):
  (self.root/'phone-app-flows.json').write_text(json.dumps({'test.swiggy':{'add-address':{'fingerprint':'1:100','status':'validated','steps':[]}}}))
  self.fingerprint='2:200';r=self.run_cli('sync');self.assertEqual(r.returncode,0,r.stderr)
  flow=json.loads((self.root/'phone-app-flows.json').read_text())['test.swiggy']['add-address'];self.assertEqual(flow['status'],'needs_revalidation')
 def test_screen_flag_does_not_override_phone_background_mode(self):
  r=self.run_cli('run','swiggy','open','--allow-screen');self.assertNotEqual(r.returncode,0);self.assertNotIn('apps.open',self.calls)
 def test_unverified_tap_without_screen_guard_is_rejected(self):
  route=self.root/'bad-route.json';route.write_text(json.dumps({'steps':[{'action':'openApp','app':'test.swiggy'},{'action':'tap','text':'Save'}]}))
  r=self.run_cli('record-flow','swiggy','bad',str(route));self.assertNotEqual(r.returncode,0);self.assertFalse((self.root/'phone-app-flows.json').read_text().strip()!='{}')
if __name__=='__main__':unittest.main()

#!/usr/bin/env python3
"""Versioned phone app context and shortcuts. UI routes require explicit foreground use."""
import argparse,json,os,re,sys,urllib.request
from pathlib import Path
BASE=Path(__file__).resolve().parent
CACHE=BASE/'phone-app-registry.json'
ROUTES=BASE/'phone-app-flows.json'
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--server',default=os.environ.get('MOBILEMCP_URL'))
p.add_argument('--device-name',default='Preview')
p.add_argument('--config',default=str(Path.home()/'.codex/config.toml'))
s=p.add_subparsers(dest='command',required=True)
s.add_parser('sync');s.add_parser('list')
for cmd in ['context','shortcuts']:
 a=s.add_parser(cmd);a.add_argument('app')
a=s.add_parser('run');a.add_argument('app');a.add_argument('shortcut');a.add_argument('--allow-screen',action='store_true');a.add_argument('--query',default='')
a=s.add_parser('record-flow');a.add_argument('app');a.add_argument('name');a.add_argument('file',type=Path)
args=p.parse_args()
token=os.environ.get('MOBILEMCP_TOKEN')
if not token or not args.server:
 try:
  config=Path(args.config).read_text();section=re.search(r'\[mcp_servers\.mobile\]([\s\S]*?)(?=\n\[|\Z)',config).group(1)
  if not token:token=re.search(r'Authorization\s*=\s*"Bearer ([^"]+)"',section).group(1)
  if not args.server:args.server=re.search(r'url\s*=\s*"([^"]+)"',section).group(1)
 except (OSError,AttributeError):sys.exit('Provide MOBILEMCP_URL and MOBILEMCP_TOKEN or the Codex mobile config.')
url=args.server.removesuffix('/mcp').rstrip('/')+'/api/call'
def call(action,params=None,device=None):
 body={'action':action,'params':params or {}}
 if device:body['deviceId']=device
 req=urllib.request.Request(url,data=json.dumps(body).encode(),headers={'Authorization':'Bearer '+token,'Content-Type':'application/json'})
 with urllib.request.urlopen(req,timeout=30) as r:d=json.load(r)
 if 'error' in d:raise RuntimeError(d['error'])
 return d['result']
def save(path,data):
 tmp=path.with_suffix('.tmp');tmp.write_text(json.dumps(data,indent=2)+'\n');tmp.chmod(0o600);tmp.replace(path)
def matching(profiles,query):
 q=query.lower();exact=[v for v in profiles if v['package'].lower()==q or v['label'].lower()==q or re.sub(r'[^a-z0-9]+','-',v['label'].lower()).strip('-')==q]
 matches=exact or [v for v in profiles if q in v['label'].lower() or q in v['package'].lower()]
 if not matches:raise RuntimeError(f'No installed app matches {query!r}. Install the app on this phone, or use list to find its label/package. Context refreshes after installation.')
 if len(matches)>1:raise RuntimeError('App query is ambiguous: '+', '.join(v['package'] for v in matches[:8]))
 return matches[0]
try:
 devices=call('hub.listDevices')['devices'];matches=[x for x in devices if x['name']==args.device_name]
 if len(matches)!=1:raise RuntimeError('Exactly one connected device must match --device-name.')
 device=matches[0]['id']
 catalog=call('apps.list',{'context':True,'limit':500},device)
 if 'profiles' not in catalog:raise RuntimeError('This companion app does not support the app registry yet.')
 flows=json.loads(ROUTES.read_text()) if ROUTES.exists() else {}
 for profile in catalog['profiles']:
  profile['alias']=re.sub(r'[^a-z0-9]+','-',profile['label'].lower()).strip('-') or profile['package']
  for route in flows.get(profile['package'],{}).values():
   route['status']='needs_revalidation' if route.get('fingerprint')!=profile['fingerprint'] else route.get('status','needs_learning')
 save(CACHE,catalog);save(ROUTES,flows)
 if args.command=='sync':print(json.dumps({'apps':catalog['total'],'revision':catalog['revision'],'cached':str(CACHE)}))
 elif args.command=='list':print(json.dumps([{'alias':x['alias'],'package':x['package'],'version':x['versionName']} for x in catalog['profiles']],indent=2))
 else:
  profile=matching(catalog['profiles'],args.app);pkg=profile['package']
  if args.command=='context':print(json.dumps({**profile,'flows':flows.get(pkg,{})},indent=2))
  elif args.command=='shortcuts':print(json.dumps({'built_in':profile['shortcuts'],'learned':flows.get(pkg,{})},indent=2))
  elif args.command=='record-flow':
   route=json.loads(args.file.read_text());steps=route.get('steps',[])
   if not steps or len(steps)>20:raise RuntimeError('A route needs 1–20 steps.')
   for step in steps:
    if step.get('action') not in ['openApp','tap','waitFor','key']:raise RuntimeError('Unsupported route step.')
    if step.get('action')=='tap' and (not step.get('text') or not step.get('expect')):raise RuntimeError('Each tap needs stable text and an expected screen label.')
    if step.get('action')=='openApp' and step.get('app')!=pkg:raise RuntimeError('App launches must target the profiled package.')
    if step.get('action')=='key' and step.get('key')!='back':raise RuntimeError('Only back is allowed in recorded routes.')
   if steps[0].get('action')!='openApp':raise RuntimeError('Start the route with openApp to establish its target.')
   route.update(fingerprint=profile['fingerprint'],mode='ui_workflow',status='needs_validation',provenance='user supplied route')
   flows.setdefault(pkg,{})[args.name]=route;save(ROUTES,flows)
   print('Recorded candidate route. Run explicitly in foreground to validate it.')
  elif args.command=='run':
   if args.shortcut=='context':print(json.dumps(profile,indent=2))
   elif args.shortcut=='notifications':print(json.dumps(call('notifications.list',{'package':pkg,'query':args.query,'limit':10,'includeOngoing':True},device),indent=2))
   else:
    if not args.allow_screen:raise RuntimeError('This shortcut uses the screen; pass --allow-screen for foreground execution.')
    state=call('device.info',{},device)
    if state.get('backgroundOnly') or state.get('locked'):raise RuntimeError('Unlock the phone and turn off Background only locally before foreground execution.')
    if args.shortcut=='open':print(json.dumps(call('apps.open',{'app':pkg,'observe':False},device),indent=2))
    else:
     route=flows.get(pkg,{}).get(args.shortcut)
     if not route:raise RuntimeError('No learned route exists; use record-flow after inspecting it once.')
     if route.get('fingerprint')!=profile['fingerprint']:raise RuntimeError('App changed: re-learn or revalidate this route before running it.')
     if route.get('status') not in ['validated','needs_validation']:raise RuntimeError('This route needs learning; it is not executable.')
     completed=0
     for step in route['steps']:
      if step['action']=='tap':
       observed=call('screen.waitFor',{'text':step['expect'],'timeout':3000},device)
       if observed.get('error'):raise RuntimeError(observed['error'])
       call('interact.tap',{'text':step['text'],'observe':False},device)
      elif step['action']=='openApp':call('apps.open',{'app':pkg,'observe':False},device)
      elif step['action']=='waitFor':
       observed=call('screen.waitFor',{'text':step['text'],'timeout':3000},device)
       if observed.get('error'):raise RuntimeError(observed['error'])
      else:call('interact.key',{'key':'back','observe':False},device)
      completed+=1
     route['status']='validated';save(ROUTES,flows)
     print(json.dumps({'completed':completed,'route':args.shortcut,'mode':'ui_workflow'}))
except Exception as e:sys.exit(str(e))

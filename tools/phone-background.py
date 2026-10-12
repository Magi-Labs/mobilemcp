#!/usr/bin/env python3
"""Background commands for the MobileMCP Preview device; no screen automation."""
import argparse,json,os,re,sys,urllib.request
from pathlib import Path
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--server',default=os.environ.get('MOBILEMCP_URL'))
p.add_argument('--device-name',default='Preview')
p.add_argument('--config',default=str(Path.home()/'.codex/config.toml'))
s=p.add_subparsers(dest='command',required=True)
s.add_parser('status')
s.add_parser('otp-permission')
s.add_parser('command-access')
s.add_parser('run-command')
a=s.add_parser('apps');a.add_argument('--query',default='');a.add_argument('--limit',type=int,default=30)
a=s.add_parser('notifications');a.add_argument('--otp',action='store_true');a.add_argument('--package',required=True);a.add_argument('--query',default='');a.add_argument('--limit',type=int,default=10)
args=p.parse_args()
token=os.environ.get('MOBILEMCP_TOKEN')
if not token or not args.server:
 try:
  config=Path(args.config).read_text()
  section=re.search(r'\[mcp_servers\.mobile\]([\s\S]*?)(?=\n\[|\Z)',config).group(1)
  if not token:token=re.search(r'Authorization\s*=\s*"Bearer ([^"]+)"',section).group(1)
  if not args.server:args.server=re.search(r'url\s*=\s*"([^"]+)"',section).group(1)
 except (OSError,AttributeError):sys.exit('Provide MOBILEMCP_URL and MOBILEMCP_TOKEN or a compatible Codex mobile configuration.')
base=args.server.removesuffix('/mcp').rstrip('/')
def call(action,params=None,device=None):
 data={'action':action,'params':params or {}}
 if device:data['deviceId']=device
 req=urllib.request.Request(base+'/api/call',data=json.dumps(data).encode(),headers={'Authorization':'Bearer '+token,'Content-Type':'application/json'})
 with urllib.request.urlopen(req,timeout=45) as r:result=json.load(r)
 if 'error' in result:raise RuntimeError(result['error'])
 return result['result']
try:
 devices=call('hub.listDevices')['devices']
 matches=[x for x in devices if x['name']==args.device_name]
 if len(matches)!=1:sys.exit('Exactly one connected device must match --device-name.')
 device=matches[0]['id']
 command_request=json.load(sys.stdin) if args.command=='run-command' else {}
 if args.command=='run-command':
  if set(command_request)-{'argv','timeout'}:raise ValueError('Unsupported command parameters')
  argv=command_request.get('argv')
  if not isinstance(argv,list) or not 1<=len(argv)<=64 or any(not isinstance(x,str) or len(x)>4096 or '\0' in x for x in argv):raise ValueError('Invalid argv')
  timeout=command_request.get('timeout',10000)
  if type(timeout) is not int or not 1000<=timeout<=30000:raise ValueError('Invalid timeout')
 action,params={'status':('device.info',{}),'otp-permission':('notifications.list',{'otpPermission':True}),'command-access':('shell.status',{}),'run-command':('shell.run',command_request),'apps':('apps.list',{'query':getattr(args,'query',''),'limit':getattr(args,'limit',30)}),'notifications':('notifications.list',{'package':getattr(args,'package',''),'query':getattr(args,'query',''),'limit':getattr(args,'limit',10),'includeOngoing':True,'otp':getattr(args,'otp',False)})}[args.command]
 print(json.dumps(call(action,params,device),indent=2))
except Exception as e:sys.exit(str(e))

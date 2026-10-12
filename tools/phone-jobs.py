#!/usr/bin/env python3
"""Durable, read-only MobileMCP background jobs. No UI, shell, payments or OTP extraction."""
import argparse,fcntl,json,os,re,sqlite3,subprocess,sys,time,urllib.request,uuid
from pathlib import Path
DB=Path(os.environ.get('MOBILE_AGENT_DB',str(Path(__file__).resolve().parent/'phone-jobs.sqlite')))
DB.parent.mkdir(parents=True,exist_ok=True)
conn=sqlite3.connect(DB,timeout=10);os.chmod(DB,0o600)
conn.row_factory=sqlite3.Row
conn.execute('CREATE TABLE IF NOT EXISTS jobs (id TEXT PRIMARY KEY, key TEXT UNIQUE, action TEXT, params TEXT, device TEXT, status TEXT, created REAL, updated REAL, result TEXT, error TEXT)');conn.commit()
def emit(v):print(json.dumps(v),flush=True)
def public(row):
 if not row:return None
 d=dict(row);d.pop('key',None)
 for k in ['params','result']:
  if d[k] is not None:d[k]=json.loads(d[k])
 return d
def rpc(action,params,device=None):
 token=os.environ.get('MOBILEMCP_TOKEN');url=os.environ.get('MOBILEMCP_URL')
 if not token or not url:
  s=Path.home().joinpath('.codex/config.toml').read_text();section=re.search(r'\[mcp_servers\.mobile\]([\s\S]*?)(?=\n\[|\Z)',s).group(1)
  token=token or re.search(r'Authorization\s*=\s*"Bearer ([^"]+)"',section).group(1)
  url=url or re.search(r'url\s*=\s*"([^"]+)"',section).group(1)
 body={'action':action,'params':params}
 if device:body['deviceId']=device
 req=urllib.request.Request(url.removesuffix('/mcp').rstrip('/')+'/api/call',data=json.dumps(body).encode(),headers={'Authorization':'Bearer '+token,'Content-Type':'application/json'})
 with urllib.request.urlopen(req,timeout=25) as r:d=json.load(r)
 if 'error' in d:raise RuntimeError(d['error'])
 return d['result']
def validate(action,params):
 if not isinstance(params,dict):raise ValueError('Parameters must be an object.')
 if action not in ['status','apps','context','notifications']:raise ValueError('Only read-only background actions are supported.')
 allowed={'status':set(),'apps':{'query','limit'},'context':{'query','limit'},'notifications':{'package','query','limit'}}[action]
 if set(params)-allowed:raise ValueError('Unsupported parameters.')
 if action=='notifications' and not re.fullmatch(r'[A-Za-z0-9_.]+',str(params.get('package',''))):raise ValueError('Notifications require an explicit package.')
 if 'limit' in params and (type(params['limit']) is not int or not 1<=params['limit']<=100):raise ValueError('Limit must be 1–100.')
 for k in ['query','package']:
  if k in params and (not isinstance(params[k],str) or len(params[k])>200):raise ValueError('Invalid '+k)
def start_worker():
 if os.environ.get('MOBILE_AGENT_AUTOSTART')=='0':return
 with open(os.devnull,'w') as out:subprocess.Popen([sys.executable,str(Path(__file__).resolve()),'worker'],stdout=out,stderr=out,start_new_session=True,env=os.environ.copy())
def worker():
 lock=open(str(DB)+'.lock','a');os.chmod(str(DB)+'.lock',0o600)
 try:fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
 except BlockingIOError:return
 lock.seek(0);lock.truncate();lock.write(str(os.getpid()));lock.flush()
 conn.execute("UPDATE jobs SET status='unknown',error='Worker stopped during execution; no automatic replay',updated=? WHERE status='running'",(time.time(),));conn.commit()
 idle=time.monotonic()
 while True:
  conn.execute('BEGIN IMMEDIATE');row=conn.execute("SELECT * FROM jobs WHERE status='queued' ORDER BY created LIMIT 1").fetchone()
  if row:conn.execute("UPDATE jobs SET status='running',updated=? WHERE id=?",(time.time(),row['id']))
  conn.commit()
  if not row:
   conn.execute('DELETE FROM jobs WHERE updated<? AND status NOT IN (\'queued\',\'running\')',(time.time()-86400,));conn.commit()
   time.sleep(1);continue
  idle=time.monotonic();status='complete';result=None;error=None
  try:
   params=json.loads(row['params']);action=row['action']
   bridge={'status':'device.info','apps':'apps.list','context':'apps.list','notifications':'notifications.list'}[action]
   if action=='context':params['context']=True
   if action=='notifications':params['includeOngoing']=True
   result=rpc(bridge,params,row['device'])
  except RuntimeError as e:
   error=str(e);status='unknown' if any(x in error for x in ['TIMEOUT','DISCONNECTED','outcome may be unknown']) else 'failed'
  except Exception as e:error=type(e).__name__+': '+str(e);status='unknown'
  conn.execute("UPDATE jobs SET status=?,updated=?,result=?,error=? WHERE id=? AND status='running'",(status,time.time(),json.dumps(result) if result is not None else None,error,row['id']));conn.commit()
  conn.execute('DELETE FROM jobs WHERE updated<? AND status NOT IN (\'queued\',\'running\')',(time.time()-86400,));conn.commit()
p=argparse.ArgumentParser(description=__doc__);p.add_argument('command',choices=['submit','get','list','cancel','worker','resume']);p.add_argument('id',nargs='?');args=p.parse_args()
try:
 if args.command=='worker':worker()
 elif args.command=='resume':start_worker();emit({'worker':'started; interrupted jobs become unknown'})
 elif args.command=='submit':
  request=json.load(sys.stdin);action=request['action'];params=request.get('params',{});validate(action,params)
  key=request.get('idempotencyKey') or uuid.uuid4().hex
  if not isinstance(key,str) or not 1<=len(key)<=200:raise ValueError('Invalid idempotency key.')
  name=request.get('deviceName','Preview');devices=rpc('hub.listDevices',{})['devices'];matches=[x for x in devices if x['name']==name]
  if len(matches)!=1:raise ValueError('Exactly one connected device must match deviceName.')
  device=matches[0]['id'];encoded=json.dumps(params,sort_keys=True)
  conn.execute('BEGIN IMMEDIATE');old=conn.execute('SELECT * FROM jobs WHERE key=?',(key,)).fetchone()
  if old:
   if old['action']!=action or old['params']!=encoded or old['device']!=device:raise ValueError('Idempotency key already belongs to a different task.')
   row=old
  else:
   id=uuid.uuid4().hex;now=time.time();conn.execute('INSERT INTO jobs VALUES (?,?,?,?,?,?,?,?,?,?)',(id,key,action,encoded,device,'queued',now,now,None,None));row=conn.execute('SELECT * FROM jobs WHERE id=?',(id,)).fetchone()
  conn.commit();start_worker();emit(public(row))
 elif args.command=='get':
  row=conn.execute('SELECT * FROM jobs WHERE id=?',(args.id,)).fetchone()
  if not row:raise ValueError('Job not found.')
  emit(public(row))
 elif args.command=='list':emit([public(x) for x in conn.execute('SELECT * FROM jobs ORDER BY created DESC LIMIT 50')])
 else:
  row=conn.execute('SELECT status FROM jobs WHERE id=?',(args.id,)).fetchone()
  if not row:raise ValueError('Job not found.')
  if row['status']=='running':raise ValueError('Job already executing; wait for its outcome. Cancellation cannot undo it.')
  conn.execute("UPDATE jobs SET status='cancelled',updated=? WHERE id=? AND status='queued'",(time.time(),args.id));conn.commit();emit(public(conn.execute('SELECT * FROM jobs WHERE id=?',(args.id,)).fetchone()))
except Exception as e:
 conn.rollback();emit({'error':str(e)});sys.exit(1)

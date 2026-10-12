#!/usr/bin/env python3
"""Owner-policy-gated OTP reads. Codes and message bodies are never persisted here."""
import argparse,json,os,re,subprocess,sys,time
from pathlib import Path
POLICY=Path(os.environ.get('MOBILE_OTP_POLICY',str(Path(__file__).resolve().parent/'mobile-agent/otp-permissions.json')))
def phone_permission():
 script=os.environ.get('MOBILE_BACKGROUND_SCRIPT',str(Path(__file__).resolve().parent/'phone-background.py'))
 proc=subprocess.run([sys.executable,script,'otp-permission'],capture_output=True,text=True,timeout=30)
 if proc.returncode:raise ValueError('SOURCE_UNAVAILABLE: Cannot check the phone OTP toggle.')
 try:allowed=json.loads(proc.stdout).get('otpAllowed') is True
 except (ValueError,AttributeError):allowed=False
 if not allowed:raise ValueError('PERMISSION_REQUIRED: Enable Allow OTP reading in the phone app; older apps must be updated.')
def check(req,now=None):
 now=now or time.time()
 try:p=json.loads(POLICY.read_text())
 except (OSError,ValueError):raise ValueError('PERMISSION_REQUIRED: Owner OTP policy is missing or invalid.')
 if p.get('enabled') is not True:raise ValueError('PERMISSION_REQUIRED: OTP permission is disabled.')
 if p.get('expiresAt') and now>=p['expiresAt']:raise ValueError('PERMISSION_EXPIRED: Owner OTP permission expired.')
 source=req.get('source');purpose=req.get('purpose')
 if source not in p.get('sources',[]) or purpose not in p.get('purposes',[]):raise ValueError('PERMISSION_REQUIRED: Source or purpose is outside the owner grant.')
 for key in ['task','service','sender']:
  if not isinstance(req.get(key),str) or not 2<=len(req[key])<=200:raise ValueError('INVALID_SCOPE: Supply task, service and expected sender.')
 if source=='notification' and req.get('package') not in p.get('packages',[]):raise ValueError('PERMISSION_REQUIRED: Notification package is outside the owner grant.')
 since=req.get('since')
 if type(since) not in [int,float] or not now-600<=since<=now+5:raise ValueError('INVALID_SCOPE: since must be the task challenge time within the last ten minutes (Unix seconds).')
 phone_permission()
 return {'allowed':True,'source':source,'purpose':purpose,'service':req['service'],'maxAgeSeconds':min(p.get('maxAgeSeconds',300),600),'scope':'OTP for the stated user-authorized task only','policyVersion':p.get('version',1)}
def extract(req,messages,now=None):
 now=now or time.time();grant=check(req,now);found=[]
 for m in messages:
  stamp=m.get('time',0)
  if type(stamp) not in [int,float] or stamp<max(req['since'],now-grant['maxAgeSeconds']) or stamp>now+5:continue
  if req['source']=='notification' and m.get('package')!=req['package']:continue
  sender=str(m.get('sender','')).strip().casefold()
  if sender!=req['sender'].strip().casefold():continue
  text=str(m.get('text',''))
  if not re.search(r'(?<!\w)'+re.escape(req['service'])+r'(?!\w)',text,re.I):continue
  if not re.search(r'\b(otp|one[ -]time|verification code|security code|authentication code|login code|passcode)\b',text,re.I):continue
  codes=set(re.findall(r'(?<![\w])([0-9]{4,8})(?![\w])',text))
  if len(codes)>1:raise ValueError('AMBIGUOUS_OTP: Multiple numbers match; no code returned.')
  if codes:found.append((next(iter(codes)),stamp))
 if len(set(c for c,_ in found))>1:raise ValueError('AMBIGUOUS_OTP: Multiple fresh codes match; no code returned.')
 if not found:return {'status':'not_found','reason':'No fresh, unambiguous OTP matched the authorized sender and service.'}
 code,stamp=max(found,key=lambda x:x[1])
 return {'status':'found','code':code,'source':req['source'],'service':req['service'],'receivedAt':stamp,'useOnlyForTask':req['task']}
def main():
 parser=argparse.ArgumentParser();parser.add_argument('command',choices=['permission','read','email']);args=parser.parse_args()
 try:
  req=json.load(sys.stdin);grant=check(req)
  if args.command=='permission':result=grant
  elif args.command=='email':
   if req['source']!='email':raise ValueError('INVALID_SOURCE: email source required.')
   messages=req.get('messages',[])
   if not isinstance(messages,list) or len(messages)>20:raise ValueError('INVALID_MESSAGES: At most 20 connector messages.')
   result=extract(req,messages)
  else:
   if req['source']!='notification':raise ValueError('INVALID_SOURCE: Notification source required.')
   script=os.environ.get('MOBILE_BACKGROUND_SCRIPT',str(Path(__file__).resolve().parent/'phone-background.py'))
   proc=subprocess.run([sys.executable,script,'notifications','--otp','--package',req['package'],'--query',req['service'],'--limit','100'],capture_output=True,text=True,timeout=30)
   if proc.returncode:raise ValueError('SOURCE_UNAVAILABLE: Notification read failed; check connection and Android notification access.')
   messages=[]
   for n in json.loads(proc.stdout).get('notifications',[]):
    messages.append({'time':n.get('time',0)/1000,'package':n.get('package'),'sender':n.get('title',''),'text':'\n'.join(str(n.get(k,'')) for k in ['title','text','lines','subText'])})
   result=extract(req,messages)
  print(json.dumps(result))
 except Exception as e:
  # Never echo provider response bodies, request fields or OTPs in errors.
  message=str(e) if isinstance(e,ValueError) else 'SOURCE_UNAVAILABLE: '+type(e).__name__
  print(json.dumps({'error':message}));sys.exit(1)
if __name__=='__main__':main()

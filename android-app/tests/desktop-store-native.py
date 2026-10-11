#!/usr/bin/env python3
"""macOS-only integration: real Foundation enumeration/files and read-only SQLite fixtures."""
from pathlib import Path
import json
import sqlite3
import subprocess
import tempfile
import time

root = Path(__file__).resolve().parents[2]
fixture = Path(tempfile.mkdtemp(prefix='agent-control-native-store-'))
prompt = '归属验证\n\n[Agent Control request 12345678-1234-1234-1234-123456789abc]'
now = int(time.time() * 1000)
connection = sqlite3.connect(fixture / 'teleagent.db')
connection.executescript('CREATE TABLE message(id TEXT,session_id TEXT,time_created INTEGER,data TEXT);CREATE TABLE part(id TEXT,message_id TEXT,session_id TEXT,time_created INTEGER,data TEXT);')
for identity, session, stamp, data in [('u', 'phone', now, {'role': 'user'}), ('a', 'phone', now+1, {'role': 'assistant', 'parentID': 'u', 'time': {'completed': now+2}, 'finish': 'stop'}), ('pc-a', 'pc', now+3, {'role': 'assistant', 'parentID': 'pc-u', 'time': {'completed': now+4}, 'finish': 'stop'})]:
    connection.execute('INSERT INTO message VALUES(?,?,?,?)', (identity, session, stamp, json.dumps(data)))
for identity, message, session, stamp, text in [('p1', 'u', 'phone', now, prompt), ('p2', 'a', 'phone', now+1, 'OWN_REPLY'), ('p3', 'pc-a', 'pc', now+2, 'FOREIGN_REPLY')]:
    connection.execute('INSERT INTO part VALUES(?,?,?,?,?)', (identity, message, session, stamp, json.dumps({'type': 'text', 'text': text})))
connection.commit()
logs = [{'type': 'message', 'id': 'u', 'sessionId': 'phone', 'role': 'user', 'content': [{'type': 'input_text', 'text': '<context>desktop</context>\n'+prompt+'\n<reminder>context</reminder>'}]}, {'type': 'message', 'id': 'a', 'parentId': 'u', 'sessionId': 'phone', 'role': 'assistant', 'status': 'completed', 'content': [{'type': 'output_text', 'text': 'OWN_REPLY'}]}, {'type': 'message', 'id': 'pc-a', 'parentId': 'pc-u', 'sessionId': 'pc', 'role': 'assistant', 'status': 'completed', 'content': [{'type': 'output_text', 'text': 'FOREIGN_REPLY'}]}]
(fixture / 'buddy.jsonl').write_text('\n'.join(json.dumps(row) for row in logs)+'\n')
source = "ObjC.import('Foundation');\n" + (root / 'android-app/app/src/main/assets/desktop-session.js').read_text()
setup = '\nvar fixture='+json.dumps(str(fixture))+';var prompt='+json.dumps(prompt)+';'
setup += "if(sessionFiles(fixture,'.db').length!==1)throw Error('Real enumeration failed to terminate');var request={app:'TeleAgent'};"
check = "var one=storedSession(prompt,{file:fixture+'/teleagent.db',session:'phone',id:'u'});if(one.text!=='OWN_REPLY'||!one.complete)throw Error('SQLite association failed');request.app='WorkBuddy';var two=storedSession(prompt,{file:fixture+'/buddy.jsonl',session:'phone',id:'u'});if(two.text!=='OWN_REPLY'||!two.complete)throw Error('JSONL association failed');console.log('PASS native enumeration terminates; SQLite and JSONL exclude foreign sessions');"
script = fixture / 'verify.js'
script.write_text(source+setup+check)
subprocess.run(['/usr/bin/osascript','-l','JavaScript',str(script)],check=True,timeout=20)
connection.execute('INSERT INTO message VALUES(?,?,?,?)', ('pc-insert','phone',now+5,json.dumps({'role':'user'})))
connection.execute('INSERT INTO part VALUES(?,?,?,?,?)', ('p4','pc-insert','phone',now+5,json.dumps({'type':'text','text':'PC_INSERTED_MESSAGE'})))
connection.commit()
script.write_text(source+setup+"var rejected=false;try{storedSession(prompt,{file:fixture+'/teleagent.db',session:'phone',id:'u'});}catch(e){rejected=/插入了新消息/.test(e.message);}if(!rejected)throw Error('PC inserted message was accepted');console.log('PASS native SQLite rejects another user message in the same phone session');")
subprocess.run(['/usr/bin/osascript','-l','JavaScript',str(script)],check=True,timeout=20)
connection.close()

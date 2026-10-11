from pathlib import Path
import tempfile,json,subprocess,stat
root=Path(__file__).resolve().parents[2]
fixture=Path(tempfile.mkdtemp(prefix='agent-control-continuity-'))
source="ObjC.import('Foundation');\n"+(root/'android-app/app/src/main/assets/desktop-session.js').read_text()
source+='\nvar request={app:"Doubao",conversationId:"phone-native"};var file='+json.dumps(str(fixture/'bindings'/'conversation.json'))+';'
source+='''
var state={version:1,app:request.app,conversationId:request.conversationId,prompt:'remember\\n\\n[Agent Control request 12345678-1234-1234-1234-123456789abc]',status:'complete',navigation:{url:'chrome://doubao-chat/chat/123'}};
writeDesktopConversation(file,state);
var restored=readDesktopConversation(file);if(restored.navigation.url!==state.navigation.url)throw Error('Binding not persisted');
state.status='pending';writeDesktopConversation(file,state);
var rejected=false;try{readDesktopConversation(file);}catch(_){rejected=true;}if(!rejected)throw Error('Uncertain send was resumed');
state.status='complete';writeDesktopConversation(file,state);
request.conversationId='different';rejected=false;try{readDesktopConversation(file);}catch(_){rejected=true;}if(!rejected)throw Error('Different mobile conversation accepted');
console.log('PASS native Foundation atomic binding create/replace/reload and uncertain/foreign conversation rejection');
'''
subprocess.run(['/usr/bin/osascript','-l','JavaScript','-'],input=source,text=True,check=True)
assert stat.S_IMODE((fixture/'bindings').stat().st_mode)==0o700
assert stat.S_IMODE((fixture/'bindings'/'conversation.json').stat().st_mode)==0o600
print('PASS private registry directory 0700 and binding file 0600')

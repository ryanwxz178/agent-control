#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
TASK_JAVA="${JAVA_HOME:-$PWD/.toolchain/jdk-17.0.20.1+1/Contents/Home}"
TASK_BUILD="$PWD/desktop-service/build"
mkdir -p "$TASK_BUILD/classes/assets"
"$TASK_JAVA/bin/javac" --release 17 -encoding UTF-8 -cp 'android-app/libs/json-20240303.jar:android-app/libs/jsch-0.2.26.jar:android-app/libs/bcprov-jdk18on-1.77.jar' -d "$TASK_BUILD/classes" android-app/app/src/main/java/dev/orca/pocket/{Agent,Inventory,EventDecoder,SshTransport,TaskRouter,Interactions,Attachments,InputFiles,RemoteImages,CodexSession,AcpSession,TeleAgentSession,PocketEngine,PersistentClient}.java desktop-service/src/dev/orca/pocket/*.java
python3 - <<'PY'
from pathlib import Path
import zipfile, shutil
out=Path('desktop-service/build/classes')
for name in ('json-20240303.jar','jsch-0.2.26.jar','bcprov-jdk18on-1.77.jar'):
    with zipfile.ZipFile(Path('android-app/libs')/name) as archive:
        for info in archive.infolist():
            if info.filename.startswith('META-INF/') or info.is_dir(): continue
            target=out/info.filename
            target.parent.mkdir(parents=True,exist_ok=True)
            target.write_bytes(archive.read(info))
for file in Path('android-app/app/src/main/assets').iterdir():
    if file.suffix in ('.sh','.ps1','.js') and file.name not in ('app.js','muse-chat.js'):
        shutil.copy2(file,out/'assets'/file.name)
for file in Path('desktop-service/browser').rglob('*'):
    if not file.is_file() or 'node_modules' in file.parts: continue
    relative=file.relative_to('desktop-service/browser')
    if relative.parts[0] not in ('src','package.json','package-lock.json','setup.mjs','LICENSE-OpenMuse'): continue
    target=out/'browser'/relative
    target.parent.mkdir(parents=True,exist_ok=True)
    shutil.copy2(file,target)
PY
"$TASK_JAVA/bin/jar" --create --file android-app/app/src/main/assets/agent-control-service.jar -C "$TASK_BUILD/classes" .
echo 'Built computer task service (Java 17)'

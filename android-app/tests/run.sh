#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
export JAVA_HOME="${JAVA_HOME:-$(cd .. && pwd)/.toolchain/jdk-17.0.20.1+1/Contents/Home}"
export PATH="$JAVA_HOME/bin:$PATH"
mkdir -p build/test-classes
CP='libs/json-20240303.jar:libs/jsch-0.2.26.jar:libs/bcprov-jdk18on-1.77.jar'
javac -encoding UTF-8 -cp "$CP" -d build/test-classes app/src/main/java/dev/orca/pocket/{Agent,Inventory,EventDecoder,SshTransport,TaskRouter,Interactions,Attachments,InputFiles,RemoteImages,CodexSession,AcpSession,TeleAgentSession,PocketEngine,PersistentClient}.java tests/CoreTest.java tests/InteractionTest.java tests/DesktopContinuityTest.java tests/DesktopFilesTest.java tests/TeleAgentSessionTest.java tests/InputFilesTest.java tests/WorkBuddyFilesTest.java tests/UploadConnectionTest.java tests/SshUploadTest.java tests/DuplexInputTest.java
java -ea -cp "build/test-classes:$CP" dev.orca.pocket.CoreTest "$@"

java -ea -cp "build/test-classes:$CP" dev.orca.pocket.InteractionTest

java -ea -cp "build/test-classes:$CP" dev.orca.pocket.DesktopContinuityTest "$(cd .. && pwd)"

java -ea -cp "build/test-classes:$CP" dev.orca.pocket.DesktopFilesTest "$(cd .. && pwd)"

java -ea -cp "build/test-classes:$CP" dev.orca.pocket.TeleAgentSessionTest tests/teleagent-headless-events.json

java -ea -cp "build/test-classes:$CP" dev.orca.pocket.InputFilesTest "$(cd .. && pwd)"

java -ea -cp "build/test-classes:$CP" dev.orca.pocket.WorkBuddyFilesTest

java -ea -cp "build/test-classes:$CP" dev.orca.pocket.UploadConnectionTest

java -ea -cp "build/test-classes:$CP" dev.orca.pocket.SshUploadTest

java -ea -cp "build/test-classes:$CP" dev.orca.pocket.DuplexInputTest

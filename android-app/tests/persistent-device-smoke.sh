#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
TASK_ROOT="$(cd .. && pwd)"
TASK_JAVA="${JAVA_HOME:-$TASK_ROOT/.toolchain/jdk-17.0.20.1+1/Contents/Home}"
TASK_SDK="${ANDROID_HOME:-$TASK_ROOT/.toolchain/android-sdk}"
TASK_BT="$TASK_SDK/build-tools/35.0.0"
TASK_SERIAL="${ANDROID_SERIAL:-emulator-5580}"
export JAVA_HOME="$TASK_JAVA" PATH="$TASK_JAVA/bin:$PATH"
mkdir -p build/persistent-instrumentation/{classes,dex,assets}
cp ../.toolchain/ssh-test/client build/persistent-instrumentation/assets/client
if [ -n "${AGENT_CONTROL_LIVE_CARD_SCRIPT:-}" ]; then cp "$AGENT_CONTROL_LIVE_CARD_SCRIPT" build/persistent-instrumentation/assets/live-card.js; fi
if [ -n "${AGENT_CONTROL_CARD_EVENT:-}" ]; then cp "$AGENT_CONTROL_CARD_EVENT" build/persistent-instrumentation/assets/reported-card.json; fi
if [ -n "${AGENT_CONTROL_IMAGE_EVENT:-}" ]; then cp "$AGENT_CONTROL_IMAGE_EVENT" build/persistent-instrumentation/assets/image-event.json; fi
ssh-keygen -lf ../.toolchain/ssh-test/host.pub -E sha256 | awk '{print $2}' > build/persistent-instrumentation/assets/fingerprint
cat > build/persistent-instrumentation/AndroidManifest.xml <<'XML'
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="dev.orca.pocket.tests"><uses-sdk android:minSdkVersion="26" android:targetSdkVersion="35"/><application android:label="Agent Control persistent tests"/><instrumentation android:name="dev.orca.pocket.tests.PersistentDeviceSmoke" android:targetPackage="dev.orca.pocket" android:functionalTest="true"/></manifest>
XML
"$TASK_JAVA/bin/javac" --release 8 -encoding UTF-8 -cp "$TASK_SDK/platforms/android-35/android.jar" -d build/persistent-instrumentation/classes tests/PersistentDeviceSmoke.java
"$TASK_JAVA/bin/jar" cf build/persistent-instrumentation/classes.jar -C build/persistent-instrumentation/classes .
"$TASK_BT/d8" --min-api 26 --lib "$TASK_SDK/platforms/android-35/android.jar" --output build/persistent-instrumentation/dex build/persistent-instrumentation/classes.jar
"$TASK_BT/aapt2" link -o build/persistent-instrumentation/base.apk -I "$TASK_SDK/platforms/android-35/android.jar" --manifest build/persistent-instrumentation/AndroidManifest.xml -A build/persistent-instrumentation/assets
(cd build/persistent-instrumentation/dex && zip -q -u ../base.apk classes.dex)
"$TASK_BT/zipalign" -f 4 build/persistent-instrumentation/base.apk build/persistent-instrumentation/aligned.apk
"$TASK_BT/apksigner" sign --ks .signing/local.p12 --ks-pass file:.signing/password --out build/persistent-instrumentation/tests.apk build/persistent-instrumentation/aligned.apk
"$TASK_SDK/platform-tools/adb" -s "$TASK_SERIAL" install --no-incremental -r build/persistent-instrumentation/tests.apk
"$TASK_SDK/platform-tools/adb" -s "$TASK_SERIAL" shell am instrument -w -e mode "${AGENT_CONTROL_TEST_MODE:-persistent}" -e directory "$TASK_ROOT" -e java "$TASK_JAVA/bin/java" dev.orca.pocket.tests/.PersistentDeviceSmoke

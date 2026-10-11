#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT="$(cd .. && pwd)"
JAVA_HOME="${JAVA_HOME:-$ROOT/.toolchain/jdk-17.0.20.1+1/Contents/Home}"
SDK="${ANDROID_HOME:-$ROOT/.toolchain/android-sdk}"
BT="$SDK/build-tools/35.0.0"
export JAVA_HOME PATH="$JAVA_HOME/bin:$PATH"
mkdir -p build/instrumentation/{classes,dex,assets}
cp ../.toolchain/ssh-test/client build/instrumentation/assets/client
ssh-keygen -lf ../.toolchain/ssh-test/host.pub -E sha256 | awk '{print $2}' > build/instrumentation/assets/fingerprint
cat > build/instrumentation/AndroidManifest.xml <<'XML'
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="dev.orca.pocket.tests"><uses-sdk android:minSdkVersion="26" android:targetSdkVersion="35"/><application android:label="Pocket smoke tests"/><instrumentation android:name="dev.orca.pocket.tests.DeviceSmoke" android:targetPackage="dev.orca.pocket" android:functionalTest="true"/></manifest>
XML
javac --release 8 -encoding UTF-8 -cp "$SDK/platforms/android-35/android.jar" -d build/instrumentation/classes tests/DeviceSmoke.java
jar cf build/instrumentation/classes.jar -C build/instrumentation/classes .
"$BT/d8" --min-api 26 --lib "$SDK/platforms/android-35/android.jar" --output build/instrumentation/dex build/instrumentation/classes.jar
"$BT/aapt2" link -o build/instrumentation/base.apk -I "$SDK/platforms/android-35/android.jar" --manifest build/instrumentation/AndroidManifest.xml -A build/instrumentation/assets
(cd build/instrumentation/dex && zip -q -u ../base.apk classes.dex)
"$BT/zipalign" -f 4 build/instrumentation/base.apk build/instrumentation/aligned.apk
"$BT/apksigner" sign --ks .signing/local.p12 --ks-pass file:.signing/password --out build/instrumentation/tests.apk build/instrumentation/aligned.apk
TASK_VERSION=$(sed -n 's/.*android:versionName="\([^"]*\)".*/\1/p' app/src/main/AndroidManifest.xml | head -n 1)
TASK_SERIAL="${ANDROID_SERIAL:-emulator-5580}"
"$SDK/platform-tools/adb" -s "$TASK_SERIAL" install --no-incremental -r "../dist/Agent-Control-$TASK_VERSION.apk"
"$SDK/platform-tools/adb" -s "$TASK_SERIAL" install --no-incremental -r build/instrumentation/tests.apk
"$SDK/platform-tools/adb" -s "$TASK_SERIAL" shell am instrument -w -e suite "${1:-all}" dev.orca.pocket.tests/.DeviceSmoke

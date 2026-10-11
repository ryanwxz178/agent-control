#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")"
ROOT="$(cd .. && pwd)"
bash "$ROOT/desktop-service/build.sh"
export JAVA_HOME="${JAVA_HOME:-$ROOT/.toolchain/jdk-17.0.20.1+1/Contents/Home}"
SDK="${ANDROID_HOME:-$ROOT/.toolchain/android-sdk}"
BT="$SDK/build-tools/35.0.0"
JAR="$SDK/platforms/android-35/android.jar"
export PATH="$JAVA_HOME/bin:$PATH"
VERSION=$(sed -n 's/.*android:versionName="\([^"]*\)".*/\1/p' app/src/main/AndroidManifest.xml | head -n 1)
if [[ ! "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then echo "Invalid manifest version" >&2; exit 1; fi
APK="../dist/Agent-Control-$VERSION.apk"
SUMS="../dist/SHA256SUMS-$VERSION.txt"
mkdir -p build/classes build/dex .signing ../dist
"$BT/aapt2" compile --dir app/src/main/res -o build/resources.zip
"$BT/aapt2" link -o build/base.apk -I "$JAR" --manifest app/src/main/AndroidManifest.xml --java build/generated -A app/src/main/assets build/resources.zip
find app/src/main/java build/generated -name '*.java' > build/sources.txt
javac -encoding UTF-8 --release 8 -cp "$JAR:libs/jsch-0.2.26.jar:libs/bcprov-jdk18on-1.77.jar" -d build/classes @build/sources.txt
jar cf build/classes.jar -C build/classes .
DEX_INPUT=$( (cd build/classes && find . -name '*.class' -exec shasum -a 256 {} \; | sort; cd ../..; shasum -a 256 libs/jsch-0.2.26.jar libs/bcprov-jdk18on-1.77.jar) | shasum -a 256 | cut -d ' ' -f 1)
if [ ! -f build/dex-input.sha256 ] || [ "$(cat build/dex-input.sha256)" != "$DEX_INPUT" ]; then
    "$BT/d8" --release --min-api 26 --lib "$JAR" --output build/dex build/classes.jar libs/jsch-0.2.26.jar libs/bcprov-jdk18on-1.77.jar
    echo "$DEX_INPUT" > build/dex-input.sha256
fi
cp build/base.apk build/unsigned.apk
(cd build/dex && zip -q -u ../unsigned.apk classes*.dex)
"$BT/zipalign" -f -p 4 build/unsigned.apk build/aligned.apk
if [ ! -f .signing/local.p12 ]; then
    umask 077
    openssl rand -base64 32 > .signing/password
    keytool -genkeypair -keystore .signing/local.p12 -storepass:file .signing/password -alias pocket -keyalg RSA -keysize 3072 -validity 10000 -dname 'CN=Agent Control Local Build' >/dev/null 2>&1
fi
"$BT/apksigner" sign --ks .signing/local.p12 --ks-pass file:.signing/password --out "$APK" build/aligned.apk
"$BT/apksigner" verify --verbose "$APK"
shasum -a 256 "$APK" > "$SUMS"
echo "APK: $ROOT/dist/Agent-Control-$VERSION.apk"

#!/bin/bash
# Build MDWeb.apk without Gradle: aapt2 → javac → d8 → zipalign → apksigner.
# The signing key lives OUTSIDE the repo (MDWEB_KEYSTORE, password MDWEB_KEYPASS): every update must be signed
# with the same key or Android refuses to install it over the old one.
set -euo pipefail
cd "$(dirname "$0")"
SDK=${ANDROID_HOME:-$HOME/Android/Sdk}; BT=$SDK/build-tools/35.0.0; JAR=$SDK/platforms/android-35/android.jar
KS=${MDWEB_KEYSTORE:?set MDWEB_KEYSTORE to your signing keystore}
PASS=${MDWEB_KEYPASS:?set MDWEB_KEYPASS to its password}
rm -rf build && mkdir -p build/res build/gen build/classes
$BT/aapt2 compile --dir res -o build/res/res.zip
$BT/aapt2 link -I $JAR -A assets --manifest AndroidManifest.xml -o build/base.apk --java build/gen build/res/res.zip --auto-add-overlay
javac -source 17 -target 17 -Xlint:-options -encoding UTF-8 -classpath $JAR -d build/classes \
      $(find src build/gen -name '*.java')
$BT/d8 --release --min-api 26 --lib $JAR --output build $(find build/classes -name '*.class')
cp build/base.apk build/unsigned.apk
python3 -c "import zipfile; zipfile.ZipFile('build/unsigned.apk','a').write('build/classes.dex','classes.dex')"
$BT/zipalign -f -p 4 build/unsigned.apk build/aligned.apk
if [ ! -f "$KS" ]; then
  mkdir -p "$(dirname "$KS")"; chmod 700 "$(dirname "$KS")"
  keytool -genkeypair -keystore "$KS" -alias mdweb -keyalg RSA -keysize 3072 -validity 36500 \
          -storepass "$PASS" -keypass "$PASS" -dname "CN=MDWeb, O=Curry" >/dev/null 2>&1
  chmod 600 "$KS"
fi
$BT/apksigner sign --ks "$KS" --ks-key-alias mdweb --ks-pass env:MDWEB_KEYPASS --key-pass env:MDWEB_KEYPASS \
                   --out MDWeb.apk build/aligned.apk
$BT/apksigner verify MDWeb.apk && echo "built: $(pwd)/MDWeb.apk ($(du -k MDWeb.apk | cut -f1) KB)"

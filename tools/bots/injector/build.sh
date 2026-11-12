#!/bin/sh
# Injector.java → injector.dex (cihaza: /data/local/tmp/hs_injector.dex)
set -e
cd "$(dirname "$0")"
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
JAVA_HOME="$(/usr/libexec/java_home -v 17 2>/dev/null || echo "$JAVA_HOME")"
rm -rf build && mkdir -p build
"$JAVA_HOME/bin/javac" --release 11 -cp "$SDK/platforms/android-35/android.jar" -d build Injector.java
"$SDK/build-tools/35.0.0/d8" --min-api 26 --lib "$SDK/platforms/android-35/android.jar" --output build build/*.class
mv build/classes.dex injector.dex
rm -rf build
echo "built $(pwd)/injector.dex"

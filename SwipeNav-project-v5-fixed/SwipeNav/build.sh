#!/usr/bin/env bash
# Build SwipeNav and create a TWRP-flashable ZIP.
# Requires JDK 17+, Android platform 19, Android build-tools 34.0.0, and D8.
set -Eeuo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT_DIR"

SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [[ -z "$SDK" ]]; then
  echo "ERROR: Android SDK not found. Set ANDROID_SDK_ROOT or ANDROID_HOME." >&2
  exit 1
fi

PLATFORM="${ANDROID_PLATFORM:-android-19}"
BT_VERSION="${ANDROID_BUILD_TOOLS_VERSION:-34.0.0}"
JAR="$SDK/platforms/$PLATFORM/android.jar"
BT="$SDK/build-tools/$BT_VERSION"

for tool in javac keytool zip; do
  if ! command -v "$tool" >/dev/null 2>&1; then
    echo "ERROR: Required command '$tool' was not found on PATH." >&2
    exit 1
  fi
done

if [[ ! -f "$JAR" ]]; then
  echo "ERROR: Missing Android platform file: $JAR" >&2
  echo "Install it with: sdkmanager --install 'platforms;$PLATFORM'" >&2
  exit 1
fi

for tool in aapt apksigner; do
  if [[ ! -x "$BT/$tool" ]]; then
    echo "ERROR: Missing Android build tool: $BT/$tool" >&2
    echo "Install it with: sdkmanager --install 'build-tools;$BT_VERSION'" >&2
    exit 1
  fi
done

# Newer Android SDK command-line tools package D8 separately from build-tools.
D8=""
if [[ -x "$BT/d8" ]]; then
  D8="$BT/d8"
else
  while IFS= read -r candidate; do
    if [[ -x "$candidate" ]]; then
      D8="$candidate"
    fi
  done < <(find "$SDK/cmdline-tools" -type f -path '*/bin/d8' -print 2>/dev/null | sort -V)
fi
if [[ -z "$D8" ]]; then
  echo "ERROR: D8 was not found in build-tools or cmdline-tools." >&2
  echo "Install/update Android command-line tools with android-actions/setup-android." >&2
  exit 1
fi

rm -rf out
mkdir -p out/cls

mapfile -t SOURCES < <(find src/com/archdroid/swipenav -maxdepth 1 -type f -name '*.java' -print | sort)
if [[ ${#SOURCES[@]} -eq 0 ]]; then
  echo "ERROR: No Java source files found under src/com/archdroid/swipenav." >&2
  exit 1
fi

javac -source 8 -target 8 -bootclasspath "$JAR" -d out/cls "${SOURCES[@]}"
mapfile -t CLASS_FILES < <(find out/cls -type f -name '*.class' -print | sort)
if [[ ${#CLASS_FILES[@]} -eq 0 ]]; then
  echo "ERROR: javac did not produce any class files." >&2
  exit 1
fi
"$D8" --min-api 19 --output out "${CLASS_FILES[@]}"

"$BT/aapt" package -f -M AndroidManifest.xml -I "$JAR" -F out/unsigned.apk
(
  cd out
  zip -q unsigned.apk classes.dex
)

keytool -genkeypair -noprompt \
  -keystore out/k.jks \
  -storepass swipenav \
  -keypass swipenav \
  -alias k \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000 \
  -dname "CN=SwipeNav"

"$BT/apksigner" sign \
  --ks out/k.jks \
  --ks-pass pass:swipenav \
  --key-pass pass:swipenav \
  --v1-signing-enabled true \
  --v2-signing-enabled false \
  --out out/SwipeNav.apk \
  out/unsigned.apk

"$BT/apksigner" verify --verbose out/SwipeNav.apk

# Build the TWRP update package.
mkdir -p out/fz
cp -a flashable/. out/fz/
mkdir -p out/fz/system/priv-app
cp out/SwipeNav.apk out/fz/system/priv-app/SwipeNav.apk
(
  cd out/fz
  zip -qr ../SwipeNav-flash.zip .
)

test -s out/SwipeNav-flash.zip
echo "Done: $ROOT_DIR/out/SwipeNav-flash.zip"

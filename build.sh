#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT"

SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Android/Sdk}}"
if [[ ! -d "$SDK_ROOT" && -d /root/android-sdk ]]; then
    SDK_ROOT=/root/android-sdk
fi

pick() {
    local candidate
    for candidate in "$@"; do
        if [[ -n "$candidate" && -x "$candidate" ]]; then
            printf '%s\n' "$candidate"
            return 0
        fi
    done
    return 1
}

ANDROID_JAR="${ANDROID_JAR:-}"
if [[ -z "$ANDROID_JAR" ]]; then
    for candidate in \
        "$SDK_ROOT/platforms/android-34/android.jar" \
        "$SDK_ROOT/android-34/android.jar"; do
        if [[ -f "$candidate" ]]; then
            ANDROID_JAR="$candidate"
            break
        fi
    done
fi

JAVA_HOME="${JAVA_HOME:-}"
if [[ -n "$JAVA_HOME" && -x "$JAVA_HOME/bin/javac" ]]; then
    JAVAC="${JAVAC:-$JAVA_HOME/bin/javac}"
    JAVA="${JAVA:-$JAVA_HOME/bin/java}"
    KEYTOOL="${KEYTOOL:-$JAVA_HOME/bin/keytool}"
else
    JAVAC="${JAVAC:-$(pick "$(command -v javac || true)" /usr/lib/jvm/java-17-openjdk-*/bin/javac || true)}"
    JAVA="${JAVA:-$(pick "$(command -v java || true)" /usr/lib/jvm/java-17-openjdk-*/bin/java || true)}"
    KEYTOOL="${KEYTOOL:-$(pick "$(command -v keytool || true)" /usr/lib/jvm/java-17-openjdk-*/bin/keytool || true)}"
fi

AAPT2="${AAPT2:-$(pick \
    "$(command -v aapt2 || true)" \
    "$SDK_ROOT/build-tools/34.0.0/aapt2" \
    "$SDK_ROOT/build-tools/35.0.0/aapt2" \
    /usr/lib/android-sdk/build-tools/29.0.3/aapt2 || true)}"
ZIPALIGN="${ZIPALIGN:-$(pick \
    "$(command -v zipalign || true)" \
    "$SDK_ROOT/build-tools/34.0.0/zipalign" || true)}"
APKSIGNER="${APKSIGNER:-$(pick \
    "$(command -v apksigner || true)" \
    "$SDK_ROOT/build-tools/34.0.0/apksigner" || true)}"

R8_VERSION="${R8_VERSION:-9.5.28}"
R8_JAR="${R8_JAR:-$ROOT/.tools/r8.jar}"
if [[ ! -f "$R8_JAR" ]]; then
    mkdir -p "$(dirname "$R8_JAR")"
    echo "Downloading d8/R8 $R8_VERSION ..."
    curl -fL --retry 3 \
        "https://dl.google.com/dl/android/maven2/com/android/tools/r8/$R8_VERSION/r8-$R8_VERSION.jar" \
        -o "$R8_JAR"
fi

KEYSTORE="${KEYSTORE:-$ROOT/qingping.keystore}"
KS_PASS="${KS_PASS:-qingping}"
KEY_PASS="${KEY_PASS:-qingping}"
VERSION_CODE="${VERSION_CODE:-11}"
VERSION_NAME="${VERSION_NAME:-3.0.0}"
OUT="${OUT:-$ROOT/qingping.apk}"

for tool in "$ANDROID_JAR" "$AAPT2" "$ZIPALIGN" "$APKSIGNER" \
            "$R8_JAR" "$JAVAC" "$JAVA" "$KEYTOOL"; do
    if [[ ! -e "$tool" ]]; then
        echo "Missing build dependency: $tool" >&2
        echo "Set ANDROID_JAR / AAPT2 / ZIPALIGN / APKSIGNER / JAVA_HOME explicitly." >&2
        exit 1
    fi
done

rm -rf build
mkdir -p build/gen build/classes build/dex

echo "[1/7] compile resources"
"$AAPT2" compile --dir res -o build/res.zip

echo "[2/7] link resources and manifest"
"$AAPT2" link \
    -o build/base.apk \
    -I "$ANDROID_JAR" \
    --manifest AndroidManifest.xml \
    -R build/res.zip \
    --java build/gen \
    --min-sdk-version 26 \
    --target-sdk-version 34 \
    --version-code "$VERSION_CODE" \
    --version-name "$VERSION_NAME" \
    --auto-add-overlay

echo "[3/7] compile java"
find src build/gen -name '*.java' -print > build/sources.txt
"$JAVAC" -encoding UTF-8 -source 8 -target 8 \
    -classpath "$ANDROID_JAR" \
    -d build/classes \
    @build/sources.txt

echo "[4/7] dex"
find build/classes -name '*.class' -print0 \
    | xargs -0 "$JAVA" -cp "$R8_JAR" com.android.tools.r8.D8 \
        --release --min-api 26 --lib "$ANDROID_JAR" --output build/dex

echo "[5/7] package"
cp build/base.apk build/unsigned.apk
python3 - <<'PY_ZIP'
import shutil
import zipfile

shutil.copyfile("build/base.apk", "build/unsigned.apk")
with zipfile.ZipFile("build/unsigned.apk", "a", zipfile.ZIP_DEFLATED) as apk:
    apk.write("build/dex/classes.dex", "classes.dex")
PY_ZIP
"$ZIPALIGN" -f -p 4 build/unsigned.apk build/aligned.apk

echo "[6/7] sign"
if [[ ! -f "$KEYSTORE" ]]; then
    mkdir -p "$(dirname "$KEYSTORE")"
    "$KEYTOOL" -genkeypair -v \
        -keystore "$KEYSTORE" \
        -alias qingping \
        -keyalg RSA -keysize 2048 -validity 10000 \
        -storepass "$KS_PASS" -keypass "$KEY_PASS" \
        -dname "CN=QingPing, OU=Local, O=QingPing, C=CN" \
        -noprompt >/dev/null
fi
"$APKSIGNER" sign \
    --ks "$KEYSTORE" --ks-key-alias qingping \
    --ks-pass "pass:$KS_PASS" --key-pass "pass:$KEY_PASS" \
    --v1-signing-enabled true --v2-signing-enabled true \
    --out "$OUT" build/aligned.apk

echo "[7/7] verify"
"$APKSIGNER" verify --verbose --print-certs "$OUT" | head -20
ls -lh "$OUT"
echo "Built: $OUT"

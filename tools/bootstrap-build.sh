#!/usr/bin/env bash
# Installs a complete Android build toolchain into /scratch/work and prints the env exports.
#
# Written for ephemeral sandboxes (CI runners, agent workspaces) where nothing persists between
# runs. Idempotent: re-running reuses whatever is already downloaded.
#
#   source tools/bootstrap-build.sh
#   cd <project> && gradle assembleDebug
#
set -uo pipefail

WORK=${WORK:-/scratch/work}
TOOLCHAIN="$WORK/toolchain"
SDK="$WORK/android-sdk"
GRADLE_HOME_DIR="$WORK/.gradle"

JDK_URL="https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.12%2B7/OpenJDK17U-jdk_x64_linux_hotspot_17.0.12_7.tar.gz"
GRADLE_URL="https://services.gradle.org/distributions/gradle-8.9-bin.zip"
CMDLINE_URL="https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
REPO="https://dl.google.com/android/repository"

mkdir -p "$TOOLCHAIN" "$SDK/platforms" "$SDK/build-tools" "$GRADLE_HOME_DIR"

# The JVM reserves address space eagerly; some sandboxes cap it below what Gradle needs.
ulimit -v unlimited 2>/dev/null || true

fetch() { # fetch <url> <dest>
  [ -s "$2" ] && return 0
  curl -fsSL --retry 3 --retry-delay 2 -o "$2.part" "$1" && mv "$2.part" "$2"
}

# ---- JDK 17 -------------------------------------------------------------------
if [ ! -x "$TOOLCHAIN/jdk/bin/java" ]; then
  echo "==> JDK 17"
  fetch "$JDK_URL" "$WORK/jdk.tar.gz"
  tar xzf "$WORK/jdk.tar.gz" -C "$WORK"
  rm -rf "$TOOLCHAIN/jdk"
  mv "$WORK"/jdk-17* "$TOOLCHAIN/jdk"
  rm -f "$WORK/jdk.tar.gz"
fi
export JAVA_HOME="$TOOLCHAIN/jdk"
export PATH="$JAVA_HOME/bin:$PATH"

# ---- Gradle 8.9 ---------------------------------------------------------------
if [ ! -x "$TOOLCHAIN/gradle/bin/gradle" ]; then
  echo "==> Gradle 8.9"
  fetch "$GRADLE_URL" "$WORK/gradle.zip"
  unzip -q -o "$WORK/gradle.zip" -d "$WORK"
  rm -rf "$TOOLCHAIN/gradle"
  mv "$WORK/gradle-8.9" "$TOOLCHAIN/gradle"
  rm -f "$WORK/gradle.zip"
fi
export PATH="$TOOLCHAIN/gradle/bin:$PATH"

# ---- Android SDK --------------------------------------------------------------
if [ ! -d "$SDK/platforms/android-35" ]; then
  echo "==> Android platform 35"
  fetch "$REPO/platform-35_r02.zip" "$WORK/platform-35.zip"
  unzip -q -o "$WORK/platform-35.zip" -d "$SDK/platforms"
  rm -f "$WORK/platform-35.zip"
fi

if [ ! -d "$SDK/build-tools/35.0.1" ]; then
  echo "==> Android build-tools 35.0.1"
  fetch "$REPO/build-tools_r35.0.1_linux.zip" "$WORK/build-tools.zip"
  unzip -q -o "$WORK/build-tools.zip" -d "$WORK/bt"
  # the archive extracts to a codename directory; Android tooling expects the version number
  rm -rf "$SDK/build-tools/35.0.1"
  mv "$WORK"/bt/android-* "$SDK/build-tools/35.0.1"
  rm -rf "$WORK/bt" "$WORK/build-tools.zip"
fi

if [ ! -d "$SDK/platform-tools" ]; then
  echo "==> Android platform-tools"
  fetch "$REPO/platform-tools_r37.0.1-linux.zip" "$WORK/platform-tools.zip"
  unzip -q -o "$WORK/platform-tools.zip" -d "$SDK"
  rm -f "$WORK/platform-tools.zip"
fi

mkdir -p "$SDK/licenses"
printf '\n24333f8a63b6825ea9c5514f83c2829b004d1fee\nd56f5187479451eabf01fb78af6dfcb131a6481e\n' \
  > "$SDK/licenses/android-sdk-license"
printf '\n84831b9409646a918e30573bab4c9c91346d8abd\n' > "$SDK/licenses/android-sdk-preview-license"

export ANDROID_HOME="$SDK"
export ANDROID_SDK_ROOT="$SDK"
export PATH="$PATH:$SDK/platform-tools"

# ---- Network: authenticated proxy, if the environment provides one -------------
# Gradle ignores http_proxy/HTTPS_PROXY, and the proxy may re-sign TLS with its own CA, so the
# JDK truststore has to know it. Values are read from the environment at runtime and written to
# the Gradle user home (outside any project), never into a repository.
PROXY_CA=/usr/local/share/ca-certificates/vault-egress-ca.crt
if [ -f "$PROXY_CA" ] && [ -n "${JAVA_HOME:-}" ]; then
  keytool -importcert -trustcacerts -noprompt -alias proxy-ca \
    -file "$PROXY_CA" -keystore "$JAVA_HOME/lib/security/cacerts" \
    -storepass changeit >/dev/null 2>&1 || true
fi

python3 - "$GRADLE_HOME_DIR/gradle.properties" <<'PY' || true
import os, sys, urllib.parse
raw = os.environ.get("HTTPS_PROXY") or os.environ.get("https_proxy")
if not raw:
    sys.exit(0)
u = urllib.parse.urlsplit(raw)
user = urllib.parse.unquote(u.username or "")
pw = urllib.parse.unquote(u.password or "")
lines = [
    f"systemProp.http.proxyHost={u.hostname}", f"systemProp.http.proxyPort={u.port}",
    f"systemProp.https.proxyHost={u.hostname}", f"systemProp.https.proxyPort={u.port}",
    f"systemProp.http.proxyUser={user}", f"systemProp.http.proxyPassword={pw}",
    f"systemProp.https.proxyUser={user}", f"systemProp.https.proxyPassword={pw}",
    "systemProp.http.nonProxyHosts=localhost|127.0.0.1",
]
path = sys.argv[1]
with open(path, "w") as fh:
    fh.write("\n".join(lines) + "\n")
os.chmod(path, 0o600)
PY

export GRADLE_USER_HOME="$GRADLE_HOME_DIR"

echo "==> toolchain ready"
java -version 2>&1 | head -1
gradle --version 2>/dev/null | grep -E '^Gradle' || true

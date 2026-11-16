#!/usr/bin/env bash
# Build the debug APK, install it on the connected device and start it.
#
#   ./install.sh                            # install, then open vFrida
#   ./install.sh -p com.achievo.vipshop     # install, then launch that clone
#   ./install.sh -p com.achievo.vipshop -u 0
#   ANDROID_SERIAL=3c4ffe25 ./install.sh    # pick a device
#
# Options:
#   -p, --package <pkg>  cloned package to launch after install
#                       (default: the vFrida host UI, com.android.vfrida)
#   -u, --user <id>      Android user that owns the clone (default: 0)
#   -h, --help           show this help
#
# Anything else is forwarded to gradlew, e.g. ./install.sh --console=plain.
#
# The project compiles with Java 21. A JAVA_HOME that already points at 21+
# wins; otherwise the Homebrew keg or Android Studio JBR is used.

set -euo pipefail

cd "$(dirname "$0")"

HOST_PACKAGE=com.android.vfrida
LAUNCH_PACKAGE=""
LAUNCH_USER=0
GRADLE_ARGS=()

while [ $# -gt 0 ]; do
  case "$1" in
    -p | --package)
      LAUNCH_PACKAGE="${2:?missing package after $1}"
      shift 2
      ;;
    -u | --user)
      LAUNCH_USER="${2:?missing user id after $1}"
      shift 2
      ;;
    -h | --help)
      awk 'NR == 1 { next } /^#/ { sub(/^# ?/, ""); print; next } { exit }' "$0"
      exit 0
      ;;
    *)
      GRADLE_ARGS+=("$1")
      shift
      ;;
  esac
done

MIN_JAVA=21

java_major() {
  "$1/bin/java" -version 2>&1 | sed -n 's/.*version "\([0-9][0-9]*\).*/\1/p' | head -n 1
}

jdk_candidates=(
  "${JAVA_HOME:-}"
  /usr/lib/jvm/java-21-openjdk-amd64
  /usr/lib/jvm/java-21-openjdk
  "$HOME"/tools/jdk-21*
  "$HOME"/.jdks/*
  /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
  /usr/local/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
  "/Applications/Android Studio.app/Contents/jbr/Contents/Home"
)

for candidate in "${jdk_candidates[@]}"; do
  [ -n "$candidate" ] && [ -x "$candidate/bin/java" ] || continue
  [ -x "$candidate/bin/javac" ] || continue
  major="$(java_major "$candidate")"
  if [ -n "$major" ] && [ "$major" -ge "$MIN_JAVA" ]; then
    export JAVA_HOME="$candidate"
    break
  fi
done

if [ -z "${JAVA_HOME:-}" ] || [ ! -x "$JAVA_HOME/bin/java" ]; then
  echo "no JDK $MIN_JAVA+ found, install one with: brew install openjdk@21" >&2
  exit 1
fi
major="$(java_major "$JAVA_HOME")"
if [ -z "$major" ] || [ "$major" -lt "$MIN_JAVA" ]; then
  echo "JAVA_HOME points at JDK $major, need $MIN_JAVA+: $JAVA_HOME" >&2
  exit 1
fi

echo "JAVA_HOME=$JAVA_HOME"
GRADLE_ARGS+=("-Dorg.gradle.java.home=$JAVA_HOME")
./gradlew installDebug "${GRADLE_ARGS[@]}"

adb_cmd() {
  if [ -n "${ANDROID_SERIAL:-}" ]; then
    adb -s "$ANDROID_SERIAL" "$@"
  else
    adb "$@"
  fi
}

if [ -z "$LAUNCH_PACKAGE" ]; then
  echo "starting $HOST_PACKAGE"
  adb_cmd shell am start -n "$HOST_PACKAGE/.view.main.MainActivity" >/dev/null
else
  echo "starting $LAUNCH_PACKAGE (user $LAUNCH_USER)"
  adb_cmd shell am start -n "$HOST_PACKAGE/.view.main.ShortcutActivity" \
    -a android.intent.action.MAIN \
    --es pkg "$LAUNCH_PACKAGE" --ei userId "$LAUNCH_USER" >/dev/null
fi

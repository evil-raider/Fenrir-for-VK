#!/usr/bin/env bash
set -uo pipefail
LOG="${GITHUB_WORKSPACE}/build.log"
exec > >(tee -a "$LOG") 2>&1
echo "=== CI start $(date -u) ==="
echo "PWD=$(pwd)"
echo "ANDROID_HOME=${ANDROID_HOME:-unset}"
echo "ANDROID_SDK_ROOT=${ANDROID_SDK_ROOT:-unset}"
java -version || true
SDKMANAGER="$(command -v sdkmanager || true)"
if [ -z "$SDKMANAGER" ] && [ -n "${ANDROID_HOME:-}" ]; then
  SDKMANAGER="${ANDROID_HOME}/cmdline-tools/latest/bin/sdkmanager"
fi
echo "SDKMANAGER=$SDKMANAGER"
echo "=== available SDK packages (filtered) ==="
"$SDKMANAGER" --list 2>/dev/null | grep -Ei 'platforms;android-3[4-9]|build-tools;3[4-9]|ndk;30|cmake;4' || echo "(nothing matched / list failed)"
echo "=== accept licenses ==="
yes | "$SDKMANAGER" --licenses >/dev/null 2>&1 || true
echo "=== install SDK packages ==="
"$SDKMANAGER" "platform-tools" "platforms;android-37" "build-tools;37.0.0"
echo "sdkmanager exit=$?"
echo "=== gradle :app_fenrir:tasks ==="
chmod +x ./gradlew || true
./gradlew :app_fenrir:tasks --stacktrace
STATUS=$?
echo "=== CI end (gradle exit=$STATUS) $(date -u) ==="
sleep 1
exit $STATUS

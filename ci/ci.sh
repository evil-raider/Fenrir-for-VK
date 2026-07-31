#!/usr/bin/env bash
set -uo pipefail
LOG="${GITHUB_WORKSPACE}/build.log"
exec > >(tee -a "$LOG") 2>&1

echo "=== CI start $(date -u) ==="

if [ -z "${GH_TOKEN:-}" ]; then
  echo "GH_TOKEN not set -> outdated workflow, skipping heavy build (no-op)."
  echo "Apply the finalized workflow (it passes GH_TOKEN) to run Stage 2."
  exit 0
fi

FFMPEG_VERSION="8.1"
NDK_VER="30.0.15729638"
CMAKE_VER="4.1.2"
BUILD_TOOLS="37.0.0"
FF_API="21"
CACHE_TAG="ci-cache"
APK_TAG="ci-debug"
REPO="${GITHUB_REPOSITORY}"
OUT="${GITHUB_WORKSPACE}/ci-out"
NDK_PATH="${ANDROID_HOME}/ndk/${NDK_VER}"
SDKMANAGER="$(command -v sdkmanager || echo "${ANDROID_HOME}/cmdline-tools/latest/bin/sdkmanager")"
mkdir -p "$OUT"

section(){ echo; echo "==================== $* ===================="; }

install_sdk(){
  section "Install SDK packages"
  yes | "$SDKMANAGER" --licenses >/dev/null 2>&1 || true
  "$SDKMANAGER" "platform-tools" "platforms;android-37.0" "platforms;android-37.1" "build-tools;${BUILD_TOOLS}" "ndk;${NDK_VER}" "cmake;${CMAKE_VER}" || echo "WARN: sdkmanager returned non-zero"
  if [ ! -e "${ANDROID_HOME}/platforms/android-37" ]; then
    for p in android-37.1 android-37.0; do
      if [ -d "${ANDROID_HOME}/platforms/$p" ]; then ln -sfn "$p" "${ANDROID_HOME}/platforms/android-37"; break; fi
    done
  fi
  echo "platforms:"; ls "${ANDROID_HOME}/platforms" 2>/dev/null || true
  echo "ndk exists: $([ -d "$NDK_PATH" ] && echo yes || echo NO)"
}

ensure_release(){
  gh release view "$1" --repo "$REPO" >/dev/null 2>&1 || gh release create "$1" --repo "$REPO" --title "$1" --notes "CI artifacts: $1" --prerelease || true
}

native_key(){
  local h
  h=$( { find native -type f -not -path '*/build/*' -print0 2>/dev/null | sort -z | xargs -0 sha256sum 2>/dev/null; sha256sum gradle/libs.versions.toml 2>/dev/null; echo "ff${FFMPEG_VERSION}-api${FF_API}-ndk${NDK_VER}-cmake${CMAKE_VER}"; } | sha256sum | cut -c1-16 )
  echo "native-${h}"
}

build_native_aar(){
  local nkey; nkey="$(native_key)"
  echo "native cache asset: ${nkey}.aar"
  ensure_release "$CACHE_TAG"
  mkdir -p compiled_native
  if gh release download "$CACHE_TAG" --repo "$REPO" -p "${nkey}.aar" -D /tmp 2>/dev/null; then
    section "Native aar: CACHE HIT"
    cp "/tmp/${nkey}.aar" compiled_native/native-release.aar
  else
    section "Native aar: CACHE MISS -> full native build"
    mkdir -p "$HOME/Android/Sdk/ndk"
    ln -sfn "$NDK_PATH" "$HOME/Android/Sdk/ndk/${NDK_VER}"
    chmod +x native/ffmpeg.sh native/src/main/jni/build_ffmpeg.sh 2>/dev/null || true
    section "Build ffmpeg via upstream native/ffmpeg.sh (android platform ${FF_API})"
    ( cd native && echo "${FF_API}" | bash ffmpeg.sh )
    echo "ffmpeg static libs:"; find "$HOME/ffmpeg/android-libs" -name '*.a' 2>/dev/null | sort || echo "(none!)"
    section "Enable :native and assemble release aar"
    printf '\ninclude(":native")\n' >> settings.gradle.kts
    chmod +x ./gradlew
    ./gradlew :native:assembleRelease --stacktrace --no-daemon
    local aar; aar="$(find native/build/outputs/aar -name '*.aar' 2>/dev/null | head -n1)"
    echo "built aar: ${aar:-NONE}"
    [ -n "$aar" ] || return 1
    cp "$aar" compiled_native/native-release.aar
    cp "$aar" "/tmp/${nkey}.aar"
    gh release upload "$CACHE_TAG" --repo "$REPO" --clobber "/tmp/${nkey}.aar" || echo "WARN: native aar cache upload failed"
  fi
  cp -f compiled_native/native-release.aar "$OUT/native-release.aar" 2>/dev/null || true
  echo "compiled_native:"; ls -la compiled_native || true
}

build_app(){
  section "Build :app_fenrir:assembleFenrirDebug"
  [ -f compiled_native/native-release.aar ] || { echo "ERROR: native aar missing"; return 1; }
  chmod +x ./gradlew
  ./gradlew :app_fenrir:assembleFenrirDebug --stacktrace --no-daemon
  section "Collect APK"
  find app_fenrir/build/outputs/apk -name '*.apk' 2>/dev/null | sort || true
  local apk; apk="$(find app_fenrir/build/outputs/apk -name '*ebug*.apk' 2>/dev/null | grep -i fenrir | head -n1)"
  [ -n "$apk" ] || { echo "ERROR: APK not found"; return 1; }
  cp "$apk" "$OUT/"
  ensure_release "$APK_TAG"
  gh release upload "$APK_TAG" --repo "$REPO" --clobber "$apk" || echo "WARN: apk upload failed"
  echo "APK published to release '${APK_TAG}': $(basename "$apk")"
}

install_sdk
STATUS=0
build_native_aar || STATUS=$?
if [ "$STATUS" -eq 0 ]; then build_app || STATUS=$?; fi
section "CI end status=${STATUS} $(date -u)"
sleep 1
exit "$STATUS"

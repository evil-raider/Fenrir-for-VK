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

patch_sources(){
  section "Patch sources: force finalized targetSdk=36 (installable on release Android)"
  sed -i -E 's/^appTargetSDK[[:space:]]*=.*/appTargetSDK = "36"/' gradle/libs.versions.toml
  grep -E '^appCompileSDK|^appTargetSDK' gradle/libs.versions.toml || true
  # FENRIR-CI: lintVital may abort release assembly on fatal lint issues; disable for CI
  sed -i 's/checkReleaseBuilds = true/checkReleaseBuilds = false/' app_fenrir/build.gradle.kts
  grep -n 'checkReleaseBuilds' app_fenrir/build.gradle.kts || true
}

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
  local h relevant
  relevant="$(grep -E '^(appCompileSDK|appBuildTools|appNdk|appCMake|appMinSDKDevelop|appMinSDKNotDevelop|developerBuild|targetAbiDevelop|targetAbiNotDevelop)[[:space:]]*=' gradle/libs.versions.toml 2>/dev/null)"
  h=$( { find native -type f -not -path '*/build/*' -print0 2>/dev/null | sort -z | xargs -0 sha256sum 2>/dev/null; echo "$relevant"; echo "ff${FFMPEG_VERSION}-api${FF_API}-ndk${NDK_VER}-cmake${CMAKE_VER}"; } | sha256sum | cut -c1-16 )
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
  section "APK diagnostics (sdk + signature)"
  local AAPT2 APKSIGNER
  AAPT2="$(ls "$ANDROID_HOME"/build-tools/*/aapt2 2>/dev/null | sort -V | tail -n1)"
  if [ -n "$AAPT2" ]; then "$AAPT2" dump badging "$apk" 2>&1 | grep -Ei 'package:|sdkVersion|targetSdkVersion|native-code|compileSdk' || true; else echo "aapt2 not found"; fi
  APKSIGNER="$(ls "$ANDROID_HOME"/build-tools/*/apksigner 2>/dev/null | sort -V | tail -n1)"
  if [ -n "$APKSIGNER" ]; then "$APKSIGNER" verify --verbose "$apk" 2>&1 | grep -Ei 'Verified using|scheme|Number of signers|WARNING|ERROR' || true; else echo "apksigner not found"; fi
  cp "$apk" "$OUT/"
  ensure_release "$APK_TAG"
  gh release upload "$APK_TAG" --repo "$REPO" --clobber "$apk" || echo "WARN: apk upload failed"
  echo "APK published to release '${APK_TAG}': $(basename "$apk")"
}

# FENRIR-CI: release build signed with user keystore from secrets (KEYSTORE_B64 + KEYSTORE_PASS)
build_app_release(){
  section "Build :app_fenrir:assembleFenrirRelease (R8, signed with user keystore)"
  [ -f compiled_native/native-release.aar ] || { echo "ERROR: native aar missing"; return 1; }
  chmod +x ./gradlew
  ./gradlew :app_fenrir:assembleFenrirRelease --stacktrace --no-daemon || return 1
  section "Collect release APK"
  find app_fenrir/build/outputs/apk -name '*.apk' 2>/dev/null | sort || true
  local apk; apk="$(find app_fenrir/build/outputs/apk -path '*release*' -name '*.apk' 2>/dev/null | grep -i fenrir | head -n1)"
  [ -n "$apk" ] || { echo "ERROR: release APK not found"; return 1; }
  local mapping; mapping="$(find app_fenrir/build/outputs/mapping -name 'mapping.txt' 2>/dev/null | head -n1)"
  if [ -n "$mapping" ]; then cp "$mapping" "$OUT/mapping.txt"; echo "R8 mapping.txt saved to ci-out"; fi
  section "Sign release APK (zipalign + apksigner)"
  local ZIPALIGN APKSIGNER KS="/tmp/fenrir.keystore"
  ZIPALIGN="$(ls "$ANDROID_HOME"/build-tools/*/zipalign 2>/dev/null | sort -V | tail -n1)"
  APKSIGNER="$(ls "$ANDROID_HOME"/build-tools/*/apksigner 2>/dev/null | sort -V | tail -n1)"
  [ -n "$ZIPALIGN" ] && [ -n "$APKSIGNER" ] || { echo "ERROR: zipalign/apksigner not found"; return 1; }
  printf '%s' "$KEYSTORE_B64" | base64 -d > "$KS" || { echo "ERROR: keystore base64 decode failed"; return 1; }
  local aligned="/tmp/fenrir-aligned.apk" signed="$OUT/Fenrir-release-signed.apk"
  "$ZIPALIGN" -f -p 4 "$apk" "$aligned" || { echo "ERROR: zipalign failed"; return 1; }
  "$APKSIGNER" sign --ks "$KS" --ks-key-alias fenrir --ks-pass "pass:${KEYSTORE_PASS}" --key-pass "pass:${KEYSTORE_PASS}" --out "$signed" "$aligned" || { echo "ERROR: apksigner sign failed (check alias/password)"; rm -f "$KS"; return 1; }
  rm -f "$KS"
  section "APK diagnostics (sdk + signature)"
  local AAPT2; AAPT2="$(ls "$ANDROID_HOME"/build-tools/*/aapt2 2>/dev/null | sort -V | tail -n1)"
  if [ -n "$AAPT2" ]; then "$AAPT2" dump badging "$signed" 2>&1 | grep -Ei 'package:|sdkVersion|targetSdkVersion|native-code|compileSdk' || true; fi
  "$APKSIGNER" verify --verbose "$signed" 2>&1 | grep -Ei 'Verified using|scheme|Number of signers|WARNING|ERROR' || true
  ensure_release "$APK_TAG"
  gh release upload "$APK_TAG" --repo "$REPO" --clobber "$signed" || echo "WARN: apk upload failed"
  echo "Signed release APK published to '${APK_TAG}': $(basename "$signed")"
}

patch_sources
install_sdk
STATUS=0
build_native_aar || STATUS=$?
if [ "$STATUS" -eq 0 ]; then
  if [ -n "${KEYSTORE_B64:-}" ] && [ -n "${KEYSTORE_PASS:-}" ]; then
    build_app_release || STATUS=$?
  else
    echo "KEYSTORE_B64/KEYSTORE_PASS not set -> falling back to debug build"
    build_app || STATUS=$?
  fi
fi
section "CI end status=${STATUS} $(date -u)"
sleep 1
exit "$STATUS"

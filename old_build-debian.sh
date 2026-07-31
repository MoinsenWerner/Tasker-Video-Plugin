#!/usr/bin/env bash
set -euo pipefail

GRADLE_VERSION="8.4"
ANDROID_COMMANDLINE_TOOLS_VERSION="11076708"
ANDROID_PLATFORM="android-34"
ANDROID_BUILD_TOOLS="34.0.0"
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEPS_DIR="${ROOT_DIR}/.debian-build"
GRADLE_DIR="${DEPS_DIR}/gradle-${GRADLE_VERSION}"
ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-${DEPS_DIR}/android-sdk}"

install_debian_packages() {
  if command -v apt-get >/dev/null 2>&1; then
    if [ "${EUID}" -eq 0 ]; then
      # apt-get update
      apt-get install -y ca-certificates curl unzip
    elif command -v sudo >/dev/null 2>&1; then
      # sudo apt-get update
      sudo apt-get install -y ca-certificates curl unzip
    else
      echo "apt-get dependencies required: ca-certificates curl unzip openjdk-17-jdk" >&2
      echo "Run this script as root or install them manually." >&2
      exit 1
    fi
  fi
}

download_gradle() {
  if [ ! -x "${GRADLE_DIR}/bin/gradle" ]; then
    mkdir -p "${DEPS_DIR}"
    curl -fsSL "https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip" -o "${DEPS_DIR}/gradle.zip"
    unzip -q -o "${DEPS_DIR}/gradle.zip" -d "${DEPS_DIR}"
  fi
}

download_android_sdk() {
  mkdir -p "${ANDROID_SDK_ROOT}/cmdline-tools"
  if [ ! -x "${ANDROID_SDK_ROOT}/cmdline-tools/latest/bin/sdkmanager" ]; then
    curl -fsSL "https://dl.google.com/android/repository/commandlinetools-linux-${ANDROID_COMMANDLINE_TOOLS_VERSION}_latest.zip" -o "${DEPS_DIR}/commandlinetools.zip"
    rm -rf "${ANDROID_SDK_ROOT}/cmdline-tools/latest" "${DEPS_DIR}/cmdline-tools"
    unzip -q -o "${DEPS_DIR}/commandlinetools.zip" -d "${DEPS_DIR}"
    mv "${DEPS_DIR}/cmdline-tools" "${ANDROID_SDK_ROOT}/cmdline-tools/latest"
  fi
  yes | "${ANDROID_SDK_ROOT}/cmdline-tools/latest/bin/sdkmanager" --sdk_root="${ANDROID_SDK_ROOT}" --licenses >/dev/null || true
  "${ANDROID_SDK_ROOT}/cmdline-tools/latest/bin/sdkmanager" --sdk_root="${ANDROID_SDK_ROOT}" \
    "platform-tools" \
    "platforms;${ANDROID_PLATFORM}" \
    "build-tools;${ANDROID_BUILD_TOOLS}"
}

build_app() {
  export JAVA_HOME="/usr/lib/jvm/zulu-17-amd64"
  export PATH="${JAVA_HOME}/bin:${PATH}"
  export ANDROID_HOME="${ANDROID_SDK_ROOT}"
  export ANDROID_SDK_ROOT
  "${GRADLE_DIR}/bin/gradle" -p "${ROOT_DIR}" clean test lintDebug assembleDebug
}

copy_apk() {
  mkdir -p /root/.aa
  cp /home/Tasker-Video-Plugin/app/build/outputs/apk/debug/app-debug.apk /root/.aa
  cp /home/Tasker-Video-Plugin/app/build/outputs/apk/debug/app-debug.apk /root/aaapp-debug.apk
}


install_debian_packages
download_gradle
download_android_sdk
build_app
copy_apk

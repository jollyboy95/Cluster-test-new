#!/usr/bin/env bash
#
# ClusterProbe install script - clone/pull, build the debug APK, and (if adb can reach a
# device from wherever this is running) install + launch it.
#
# Usage:
#   ./install.sh                  # build only, install if a device is already connected
#   ./install.sh 192.168.1.50:5555   # also adb-connect to the head unit over wifi first
#
# NOTE on GitHub Codespaces specifically: a Codespace runs in GitHub's cloud, with no network
# path to your car's wifi, so the adb-connect step below will simply fail there - that's
# expected, not a bug. In a Codespace this script is still useful for the clone+build steps;
# download the resulting app-debug.apk from the build and adb-install it from a machine that
# actually has line-of-sight to the head unit (your PC/phone on the same wifi, or via USB).

set -euo pipefail

REPO_URL="${CLUSTERPROBE_REPO:-https://github.com/jollyboy95/Cluster-test-new.git}"
REPO_DIR="Cluster-test-new"
PACKAGE="com.clusterprobe"
DEVICE="${1:-}"   # optional: IP:PORT for adb connect, e.g. 192.168.1.50:5555

log()  { echo -e "\033[1;33m==>\033[0m $*"; }
fail() { echo -e "\033[1;31mERROR:\033[0m $*" >&2; exit 1; }

# --- 1. Get the code ---
if [ -d "$REPO_DIR/.git" ]; then
  log "Repo already cloned, pulling latest"
  git -C "$REPO_DIR" pull --ff-only
else
  log "Cloning $REPO_URL"
  git clone "$REPO_URL" "$REPO_DIR"
fi
cd "$REPO_DIR"

# --- 2. Sanity-check the toolchain ---
command -v gradle >/dev/null 2>&1 || fail "'gradle' not found on PATH. Install Gradle 7.6+ and a JDK 17 first (or run 'gh workflow run' to build via GitHub Actions instead of locally)."
command -v java   >/dev/null 2>&1 || fail "'java' not found on PATH. Install a JDK (17 recommended) first."

if [ -z "${ANDROID_SDK_ROOT:-}${ANDROID_HOME:-}" ]; then
  log "ANDROID_SDK_ROOT/ANDROID_HOME is not set. If the build below fails looking for the SDK,"
  log "install Android cmdline-tools, export ANDROID_SDK_ROOT=/path/to/sdk, and re-run."
fi

# --- 3. Build ---
log "Building debug APK (gradle assembleDebug)"
gradle assembleDebug --no-daemon

APK_PATH="app/build/outputs/apk/debug/app-debug.apk"
[ -f "$APK_PATH" ] || fail "Build finished but $APK_PATH wasn't produced - check the gradle output above."
log "Built: $(pwd)/$APK_PATH"

# --- 4. Install, if adb can see a device from here ---
if ! command -v adb >/dev/null 2>&1; then
  log "'adb' not found here. Copy $APK_PATH to a machine with adb and run:"
  log "  adb install -r $APK_PATH"
  exit 0
fi

if [ -n "$DEVICE" ]; then
  log "Connecting to $DEVICE"
  adb connect "$DEVICE" || true
fi

if ! adb get-state >/dev/null 2>&1; then
  log "No adb device/emulator connected from here."
  log "Connect the head unit (USB, or 'adb connect <ip>:5555' over wifi) and re-run,"
  log "or manually: adb install -r $APK_PATH"
  exit 0
fi

log "Installing $PACKAGE"
adb install -r "$APK_PATH"

log "Launching MainActivity"
adb shell am start -n "$PACKAGE/.MainActivity" || log "(couldn't auto-launch - open it from the launcher instead)"

log "Done."

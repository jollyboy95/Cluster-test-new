#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "${1:-.}" && pwd)"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEST="$ROOT/ClusterProbe-2"
if [[ ! -f "$HERE/payload/app/build.gradle" ]]; then echo 'ERROR: installer payload missing' >&2; exit 1; fi
if [[ -e "$DEST" ]]; then
  BACKUP="$ROOT/ClusterProbe-2-backup-$(date +%Y%m%d-%H%M%S)"
  mv "$DEST" "$BACKUP"
  echo "Previous ClusterProbe-2 saved to: $BACKUP"
fi
mkdir -p "$DEST" "$ROOT/.github/workflows"
cp -R "$HERE/payload/." "$DEST/"
# Keep one workflow at repository root; GitHub ignores nested workflow definitions.
rm -rf "$DEST/.github"
cat > "$ROOT/.github/workflows/clusterprobe-2.yml" <<'YAML'
name: Build ClusterProbe 2 APK
on:
  push:
    branches: [main]
    paths:
      - 'ClusterProbe-2/**'
      - '.github/workflows/clusterprobe-2.yml'
  workflow_dispatch:
jobs:
  build:
    runs-on: ubuntu-latest
    defaults:
      run:
        working-directory: ClusterProbe-2
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '17'
      - uses: gradle/actions/setup-gradle@v3
        with:
          gradle-version: '7.6.4'
      - uses: android-actions/setup-android@v3
      - name: Build APK
        run: gradle assembleDebug --no-daemon
      - uses: actions/upload-artifact@v4
        with:
          name: ClusterProbe-2-debug-apk
          path: ClusterProbe-2/app/build/outputs/apk/debug/app-debug.apk
YAML
printf '\nInstalled ClusterProbe-2 as a separate project at %s\n' "$DEST"
printf 'Existing app and Shizuku integration were NOT overwritten.\n'
printf 'Push to main, then run GitHub Actions: Build ClusterProbe 2 APK.\n'

#!/usr/bin/env bash
# 起動時間・メモリ・フレームを測り、結果を docs/measurements/ に残す。変更のたびに比べるため。
# Waydroid（x86_64、GPU はホストの共有）の数値は目安。実機（POCO X7 Pro）で測り直すこと。
# 使い方: scripts/measure.sh [ラベル]
set -euo pipefail
cd "$(dirname "$0")/.."

PKG=dev.furunov.komalauncher
LABEL="${1:-$(git rev-parse --short HEAD)}"
OUT="docs/measurements/$(date +%Y%m%d-%H%M)-$LABEL.txt"
mkdir -p docs/measurements

{
  echo "# $LABEL ($(date -Iseconds))"
  echo "device: $(adb shell getprop ro.product.model) / Android $(adb shell getprop ro.build.version.release) / $(adb shell wm size | tail -1)"
  echo "apk: $(adb shell dumpsys package $PKG | grep -m1 versionName) $(adb shell dumpsys package $PKG | grep -m1 -o 'flags=\[[^]]*\]')"

  echo
  echo "## 起動（コールドスタート、5 回）"
  for i in 1 2 3 4 5; do
    adb shell am force-stop "$PKG"
    sleep 1
    adb shell am start -W -n "$PKG/.MainActivity" | grep -E 'TotalTime'
  done

  echo
  echo "## 待機中のメモリ（ホームを表示して 5 秒後）"
  adb shell input keyevent HOME
  sleep 5
  adb shell dumpsys meminfo "$PKG" | grep -E 'TOTAL PSS|Java Heap|Native Heap|Graphics|Code:|TOTAL RSS' | sed 's/^ *//'

  echo
  echo "## フォルダの開閉を 20 回"
  adb shell dumpsys gfxinfo "$PKG" reset >/dev/null
  for i in $(seq 1 20); do
    adb shell input tap 615 600
    sleep 0.6
    adb shell input keyevent BACK
    sleep 0.4
  done
  adb shell dumpsys gfxinfo "$PKG" | grep -E 'Total frames rendered|Janky frames|50th percentile|90th percentile|99th percentile' | head -5 | sed 's/^ *//'

  echo
  echo "## フォルダの開閉の後のメモリ"
  adb shell dumpsys meminfo "$PKG" | grep -E 'TOTAL PSS' | sed 's/^ *//'
} | tee "$OUT"
echo "→ $OUT"

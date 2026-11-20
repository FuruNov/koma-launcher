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

# uiautomator で属性（例: text="閉じる"）を持つ要素を探し、その中の割合 (fx, fy) の位置を返す
center_of() { # 属性 [fx] [fy]
  adb shell uiautomator dump /sdcard/koma-ui.xml >/dev/null 2>&1
  adb shell cat /sdcard/koma-ui.xml | tr '>' '\n' | grep -F "$1" | head -1 | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' |
    grep -oE '[0-9]+' | tr '\n' ' ' | awk -v fx="${2:-0.5}" -v fy="${3:-0.5}" '{ printf "%d %d", $1 + ($3 - $1) * fx, $2 + ($4 - $2) * fy }'
}
# 見つからなければ何もしない（set -e で計測の途中で止まらないよう、常に成功で返す）
tap_at() { if [ -n "$1" ]; then adb shell input tap $1; fi; }

# HOME キーでこのランチャーが開くよう、測る間だけ既定のホームにする。終わったら元のホームに戻す
prev_home=$(adb shell cmd package resolve-activity --brief -c android.intent.category.HOME -a android.intent.action.MAIN | tail -1 | tr -d "\r")
adb shell cmd package set-home-activity "$PKG/.MainActivity" >/dev/null
trap '[ -n "$prev_home" ] && adb shell cmd package set-home-activity "$prev_home" >/dev/null' EXIT

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
    adb shell input tap 850 1200
    sleep 0.6
    adb shell input keyevent BACK
    sleep 0.4
  done
  adb shell dumpsys gfxinfo "$PKG" | grep -E 'Total frames rendered|Janky frames|50th percentile|90th percentile|99th percentile' | head -5 | sed 's/^ *//'

  echo
  echo "## フォルダの開閉の後のメモリ"
  adb shell dumpsys meminfo "$PKG" | grep -E 'TOTAL PSS' | sed 's/^ *//'

  echo
  echo "## 編集画面を開き、割る・元に戻す・閉じるを 10 回した後のメモリ（使用量が増え続けないか）"
  for _ in $(seq 1 10); do
    # コマの間の余白（見本とモックアップの大ゴマで同じ位置）を長押ししてホームのメニュー
    adb shell input swipe 300 1452 300 1452 1000
    sleep 1
    tap_at "$(center_of 'text="コマ割りを編集"')"
    sleep 1.5
    tap_at "$(center_of 'content-desc="コマ割りの図"' 0.5 0.2)"
    sleep 0.5
    tap_at "$(center_of 'text="横に分割"')"
    sleep 0.5
    tap_at "$(center_of 'text="元に戻す"')"
    sleep 0.5
    tap_at "$(center_of 'text="閉じる"')"
    sleep 1
  done
  sleep 5
  adb shell dumpsys meminfo "$PKG" | grep -E 'TOTAL PSS|Java Heap|Native Heap|Graphics' | sed 's/^ *//'
} | tee "$OUT"
echo "→ $OUT"

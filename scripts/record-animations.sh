#!/usr/bin/env bash
# アニメーションの各案を Waydroid で録画する（debug ビルドと root の su が要る）。
# 設定ファイルを直接書き換えて案を切り替え、ロック解除は debug の broadcast で再生する。
# 使い方: scripts/record-animations.sh <出力先>
set -euo pipefail
OUT="${1:?出力先のディレクトリ}"
mkdir -p "$OUT"
PKG=dev.furunov.komalauncher
DATA=/data/data/$PKG/files
OWNER=$(adb shell su -c "'stat -c %u:%g $DATA'" | tr -d '\r')

use() { # enter press
  printf '{"version":1,"animation":{"enabled":true,"enter":"%s","press":"%s","speed":1.0,"staggerMs":60}}' "$1" "$2" >/tmp/koma-settings.json
  adb push /tmp/koma-settings.json /sdcard/koma-settings.json >/dev/null
  adb shell su -c "'cp /sdcard/koma-settings.json $DATA/settings.json && chown $OWNER $DATA/settings.json'"
  adb shell am force-stop $PKG
  adb shell am start -n $PKG/.MainActivity >/dev/null
  sleep 3
  adb shell input keyevent HOME
  sleep 1
}

record() { # 名前 秒数 コマンド...
  local name=$1 secs=$2
  shift 2
  adb shell screenrecord --time-limit "$secs" --bit-rate 6000000 "/sdcard/koma-$name.mp4" &
  local pid=$!
  sleep 0.6
  "$@"
  wait $pid || true
  adb pull "/sdcard/koma-$name.mp4" "$OUT/$name.mp4" >/dev/null
  adb shell rm "/sdcard/koma-$name.mp4"
  echo "$OUT/$name.mp4"
}

unlock() { adb shell am broadcast -a $PKG.DEBUG_PLAY_UNLOCK -n $PKG/.DebugReceiver >/dev/null; }
press() {
  adb shell input swipe 760 1200 760 1200 350
  sleep 1.2
  adb shell input keyevent BACK
}

for enter in slide-overshoot pop-in drop; do
  use "$enter" squash
  record "enter-$enter" 3 unlock
done
for p in squash lift; do
  use slide-overshoot "$p"
  record "press-$p" 3 press
done
use slide-overshoot squash

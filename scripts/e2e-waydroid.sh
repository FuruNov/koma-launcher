#!/usr/bin/env bash
# Waydroid（1080x1920、ウィンドウを最大化した状態）で、ホームの基本の操作を確かめる。
# 見本のレイアウト（app/src/main/assets/sample/layout.json）を前提にした座標を使う。
# 使い方: scripts/e2e-waydroid.sh [スクリーンショットの保存先]
set -uo pipefail

OUT="${1:-build/e2e}"
mkdir -p "$OUT"
PKG=dev.furunov.komalauncher
pass=0
fail=0

texts() {
  adb shell uiautomator dump /sdcard/koma-ui.xml >/dev/null 2>&1
  adb shell cat /sdcard/koma-ui.xml | grep -oE 'text="[^"]+"' | sed 's/text="//; s/"$//'
}
shot() { adb exec-out "screencap -p 2>/dev/null" >"$OUT/$1.png"; }
check() { # 名前 条件の結果(0/1)
  if [ "$2" -eq 0 ]; then
    echo "ok   $1"
    pass=$((pass + 1))
  else
    echo "FAIL $1"
    fail=$((fail + 1))
  fi
}
home() {
  adb shell input keyevent HOME
  sleep 1.2
}
has() { texts | grep -qx "$1"; }
# 条件が満たされるまで最大 3 秒待つ。アニメーションや起動の遅れで判定を誤らないように
waitfor() {
  for _ in 1 2 3 4 5 6; do
    if eval "$1"; then return 0; fi
    sleep 0.5
  done
  return 1
}
focus() { adb shell dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'; }

adb shell am start -n "$PKG/.MainActivity" >/dev/null
sleep 2
home

# 緑のコマ（連絡）の、斜めの辺のすぐ内側
adb shell input tap 615 600
sleep 1.2
shot folder-open
has Contacts
check "斜めの辺の内側を叩くとフォルダが開く" $?

adb shell input keyevent BACK
sleep 1
has Contacts
[ $? -ne 0 ]
check "戻るボタンでフォルダが閉じる" $?

# 青と緑の間の余白
adb shell input tap 590 600
sleep 1
has Contacts
[ $? -ne 0 ]
check "コマの間の余白ではフォルダが開かない" $?

adb shell input tap 615 600
sleep 1
home
has Contacts
[ $? -ne 0 ]
check "ホームボタンでフォルダが閉じる" $?

adb shell input swipe 540 1500 540 700 200
sleep 1.2
shot drawer
waitfor 'has Calculator'
check "上スワイプで全アプリ一覧が開く" $?
adb shell input keyevent BACK
sleep 1

adb shell input swipe 540 700 540 1500 200
sleep 1.2
shot shade
waitfor 'focus | grep -q NotificationShade'
check "下スワイプで通知センターが開く" $?
adb shell cmd statusbar collapse >/dev/null 2>&1
sleep 1

adb shell input tap 615 600
sleep 1
adb shell input tap 876 590
sleep 2
# Contacts は初回に権限の確認画面を出すので、それも起動できた扱いにする
waitfor "focus | grep -qiE 'contacts|permissioncontroller'"
check "フォルダのアイコンからアプリが起動する" $?
adb shell input keyevent BACK
adb shell input keyevent BACK
home

echo "pass=$pass fail=$fail"
[ "$fail" -eq 0 ]

#!/usr/bin/env bash
# Waydroid（1080x1920、ウィンドウを最大化した状態）で、ホームの基本の操作を確かめる。
# 見本のレイアウト（app/src/main/assets/sample/layout.json、version 2 の大ゴマ）を前提にした座標を使う。
# 始める前に、アプリの内部のレイアウトを消して見本に戻す（Waydroid の su を使う）。
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
# ラベルの文字の位置（中心）。アイコンを叩くときは dy で上へずらす
center_of() { # ラベル [dy]
  texts >/dev/null
  adb shell cat /sdcard/koma-ui.xml | grep -oE "text=\"$1\"[^>]*bounds=\"\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]\"" | head -1 |
    grep -oE '[0-9]+' | tail -4 | tr '\n' ' ' | awk -v dy="${2:-0}" '{ printf "%d %d", ($1 + $3) / 2, ($2 + $4) / 2 - dy }'
}
tap_text() { # ラベル [dy]
  local c
  c=$(center_of "$@")
  [ -n "$c" ] && adb shell input tap $c
}
focus() { adb shell dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'; }

# HOME キーでこのランチャーが開くよう、確かめる間だけ既定のホームにする。終わったら元のホームに戻す
prev_home=$(adb shell cmd package resolve-activity --brief -c android.intent.category.HOME -a android.intent.action.MAIN | tail -1 | tr -d "\r")
adb shell cmd package set-home-activity "$PKG/.MainActivity" >/dev/null
trap '[ -n "$prev_home" ] && adb shell cmd package set-home-activity "$prev_home" >/dev/null' EXIT
adb shell am force-stop "$PKG"
adb shell su -c "rm -rf /data/data/$PKG/files/layout.json /data/data/$PKG/files/art"
adb shell am start -n "$PKG/.MainActivity" >/dev/null
sleep 2
home

# 緑のコマ（連絡）の、斜めの辺のすぐ内側
adb shell input tap 850 1200
sleep 1.2
shot folder-open
has Contacts
check "斜めの辺の内側を叩くとフォルダが開く" $?

adb shell input keyevent BACK
sleep 1
has Contacts
[ $? -ne 0 ]
check "戻るボタンでフォルダが閉じる" $?

# ウィジェットのコマと道具のコマの間の余白
adb shell input tap 300 1454
sleep 1
has Contacts
[ $? -ne 0 ]
check "コマの間の余白ではフォルダが開かない" $?

adb shell input tap 850 1200
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

adb shell input tap 850 1200
sleep 1
tap_text Contacts 40
sleep 2
# Contacts は初回に権限の確認画面を出すので、それも起動できた扱いにする
waitfor "focus | grep -qiE 'contacts|permissioncontroller'"
check "フォルダのアイコンからアプリが起動する" $?
adb shell input keyevent BACK
adb shell input keyevent BACK
home

# その他のコマ（全アプリ一覧）
adb shell input tap 810 1680
sleep 1.2
waitfor 'has Calculator'
check "全アプリ一覧のコマを押すと一覧が開く" $?
adb shell input keyevent BACK
sleep 1

# --- フォルダの中身の編集 ---
# 長押しして動かさずに離すとメニュー
adb shell input tap 850 1200
sleep 1
read -r mx my <<<"$(center_of Messages 40)"
adb shell input swipe "$mx" "$my" "$mx" "$my" 900
sleep 1.2
waitfor 'has アプリ情報'
check "アイコンの長押しでメニューが開く" $?
adb shell input keyevent BACK
sleep 1

# 長押しのまま運ぶ: 指を下ろし、長押しの時間を待ってから少しずつ動かす
dragto() { # x1 y1 x2 y2
  adb shell "input motionevent DOWN $1 $2; sleep 0.9; input motionevent MOVE $(( ($1 * 3 + $3) / 4 )) $(( ($2 * 3 + $4) / 4 )); sleep 0.1; input motionevent MOVE $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 )); sleep 0.1; input motionevent MOVE $3 $4; sleep 0.4; input motionevent UP $3 $4"
  sleep 1.2
}
labels_in_folder() { texts | grep -vxE '連絡|よく使う|道具|メディア|その他|フォルダを変更しました|元に戻す' | tr '\n' ' '; }

# 連絡のフォルダの中で、Contacts を Messages の位置へ（並べ替え）
adb shell input tap 850 1200
sleep 1
read -r cx cy <<<"$(center_of Contacts 40)"
read -r mx my <<<"$(center_of Messages 40)"
# すでに Contacts が先なら、逆に Messages を先頭へ運んで並べ替えを確かめる
if [ "$cx" -lt "$mx" ]; then dragto "$mx" "$my" "$cx" "$cy"; first=Messages; else dragto "$cx" "$cy" "$mx" "$my"; first=Contacts; fi
order=$(labels_in_folder)
echo "     並べ替え後: $order"
[[ "$order" == "$first"* ]]
check "フォルダの中で並べ替えられる" $?
home

# 全アプリ一覧の Music を道具のコマへ
adb shell input swipe 540 1500 540 700 200
sleep 1.5
dragto 753 360 300 1650
adb shell input tap 300 1650
sleep 1
waitfor 'has Music'
check "一覧からコマへ運ぶとフォルダに加わる" $?

# 道具のフォルダの Music をよく使うのコマへ（コマ間の移動）
read -r mx my <<<"$(center_of Music 40)"
dragto "$mx" "$my" 540 500
adb shell input tap 540 500
sleep 1
waitfor 'has Music'
check "フォルダから別のコマへ移せる" $?
home

# よく使うから外し、元に戻す
adb shell input tap 540 500
sleep 1
read -r mx my <<<"$(center_of Music 40)"
dragto "$mx" "$my" 540 60
# 「元に戻す」は 5 秒で消えるので、見つけたらすぐ叩く
undo_at=$(center_of 元に戻す)
[ -n "$undo_at" ]
check "外す場所へ運ぶと外れ、元に戻すが出る" $?
[ -n "$undo_at" ] && adb shell input tap $undo_at
sleep 1
adb shell input tap 540 500
sleep 1
waitfor 'has Music'
check "元に戻すで戻る" $?
home

# --- コマ割りの編集 ---
# コマの間の余白を長押しするとホームのメニュー、そこから編集画面
open_editor() {
  adb shell input swipe 300 1454 300 1454 1000
  sleep 1
  tap_text コマ割りを編集
  sleep 2
}
# 編集画面の縮小図（content-desc）の中の、割合 (fx, fy) の位置を叩く
tap_preview() { # fx fy
  texts >/dev/null
  local b
  b=$(adb shell cat /sdcard/koma-ui.xml | grep -oE 'content-desc="コマ割りの図"[^>]*bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' | grep -oE '[0-9]+' | tail -4 | tr '\n' ' ')
  [ -n "$b" ] && adb shell input tap $(echo "$b" | awk -v fx="$1" -v fy="$2" '{ printf "%d %d", $1 + ($3 - $1) * fx, $2 + ($4 - $2) * fy }')
}
open_editor
shot editor
waitfor 'has 大ゴマ'
check "ホームのメニューから編集画面が開く" $?
# 元に戻す履歴は編集画面を開いている間だけ持つので、割って戻すまでを 1 回の中で行う
tap_preview 0.5 0.2
sleep 1
tap_text 横に割る
sleep 1
tap_text 元に戻す
sleep 1
tap_text 閉じる
sleep 1.5
! has 新しいコマ
check "元に戻すで割る前に戻る" $?
open_editor
tap_preview 0.5 0.2
sleep 1
tap_text 横に割る
sleep 1
tap_text 閉じる
sleep 1.5
shot editor-split
waitfor 'has 新しいコマ'
check "コマを割ると、ホームに新しいコマが現れる" $?

echo "pass=$pass fail=$fail"
[ "$fail" -eq 0 ]

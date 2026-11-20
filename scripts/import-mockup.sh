#!/usr/bin/env bash
# モックアップ（docs/mockups/editor.template.html）の保存状態から version 2 の layout.json を作り、
# 画像と一緒に端末の取り込み口（/sdcard/Android/data/<pkg>/files/）へ送る。
# アプリは layout.json が置かれたら画像を縮めて内部へ写し、ホームをその形にする。
#
# 使い方: scripts/import-mockup.sh <state.json> <画像のディレクトリ> [layout.json の保存先]
#   state.json: Artifact の db の editor/current（{ "state": {...} } でも、state の中身だけでもよい）
#   画像のディレクトリ: モックアップのカードの名前（pic-a-5 など）の .jpg / .jpeg / .png / .webp を置いた場所。
#     人物の切り抜きがあれば inv/<名前>.webp（白が人物以外）か mask/<名前>.png（白が人物）に置く
# 画像は版権があるので、リポジトリには入れない。
set -euo pipefail

STATE="${1:?state.json を指定してください}"
CARDS="${2:?画像のディレクトリを指定してください}"
OUT="${3:-build/mockup-layout.json}"
PKG=dev.furunov.komalauncher
DEST="/sdcard/Android/data/$PKG/files"

mkdir -p "$(dirname "$OUT")"

# モックアップの木は、葉が { panel: { id, kind, label, art: { card, zoom, dx, dy } } }、
# 枝が { cut: { ta, tb }, a, b }。アプリの形との違いは art.card → art.image だけ
jq '
  (if has("state") then .state else . end) as $s
  | def node:
      if .panel then
        { panel: (.panel
            | { id, kind, label }
              + (if .art and .art.card then { art: { image: .art.card, zoom: (.art.zoom // 1), dx: (.art.dx // 0), dy: (.art.dy // 0) } } else {} end)) }
      else
        { cut: { ta: .cut.ta, tb: .cut.tb }, a: (.a | node), b: (.b | node) }
      end;
  {
    version: 2,
    frame: {
      refWidth: 1220,
      margin: 24,
      gutter: ($s.gutter // 26),
      gutterColor: ($s.gutterColor // "#111111"),
      bleed: ($s.bleed // false),
      border: {
        style: ($s.border.style // "single"),
        width: ($s.border.width // 10),
        # モックアップの枠はいつも黒（#111111）なので、黒は自動（間隔が暗ければ白）として取り込む
        color: (if ($s.border.color // "#111111") == "#111111" then null else $s.border.color end)
      }
    },
    tree: ($s.tree | node),
    look: {
      tone: (if $s.filter == "soft" then "soft" else "none" end),
      strength: ($s.strength // 1),
      inner: ($s.shade.inner // 0),
      sheen: ($s.shade.sheen // 0)
    },
    text: {
      label: ($s.text.label // "none"),
      lang: ($s.text.lang // "en"),
      masthead: (if $s.text.masthead then ($s.text.word // "") else "" end),
      mastheadY: ($s.text.mastheadY // 0.09),
      mastheadPanel: $s.text.panel
    },
    clock: {
      enabled: (($s.clock.style // "none") != "none"),
      panel: $s.clock.panel,
      x: ($s.clock.x // 0.5),
      y: ($s.clock.y // 0.83),
      size: ($s.clock.size // 0.78)
    },
    fx: {
      parallax: ($s.fx.parallax // false),
      strength: ($s.fx.strength // 1)
    }
  }' "$STATE" >"$OUT"
echo "layout.json を作りました: $OUT"

adb shell mkdir -p "$DEST/art"
for card in $(jq -r '.. | objects | select(has("art")) | .art.image' "$OUT" | sort -u); do
  src=""
  for ext in jpg jpeg png webp; do
    if [ -f "$CARDS/$card.$ext" ]; then
      src="$CARDS/$card.$ext"
      break
    fi
  done
  if [ -z "$src" ]; then
    echo "画像がありません: $CARDS/$card.(jpg|jpeg|png|webp)" >&2
    continue
  fi
  adb push "$src" "$DEST/art/$card.${src##*.}" >/dev/null
  echo "送りました: $src"
  # 人物の切り抜き。inv/ は白が人物以外（モックアップの INV）、mask/ は白が人物（アプリが詰めて反転する）
  for kind in inv mask; do
    for ext in webp png; do
      if [ -f "$CARDS/$kind/$card.$ext" ]; then
        adb push "$CARDS/$kind/$card.$ext" "$DEST/art/$card.$kind.$ext" >/dev/null
        echo "送りました: $CARDS/$kind/$card.$ext（切り抜き）"
        break 2
      fi
    done
  done
done
# layout.json は最後に送る。届いた時点でアプリが取り込みを始めるので、画像を先に揃えておく
adb push "$OUT" "$DEST/layout.json" >/dev/null
echo "送りました: layout.json（アプリが取り込むと layout.json.imported に名前が変わります）"
